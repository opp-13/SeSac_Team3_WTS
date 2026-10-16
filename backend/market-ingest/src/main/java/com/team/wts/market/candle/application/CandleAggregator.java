package com.team.wts.market.candle.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.team.wts.market.candle.domain.Candle;
import com.team.wts.market.candle.domain.CandleRepository;
import com.team.wts.market.candle.domain.CurrentCandleStore;

import jakarta.annotation.PreDestroy;

/**
 * 시세 스트림을 1분봉으로 집계한다. (CLAUDE.md §22)
 *
 * <p>분이 바뀌는 순간 직전 봉을 확정해 DB에 저장하고 새 봉을 연다.
 *
 * <p><b>중복 이벤트에 안전하다</b> (§17). OHLC는 최대/최소/마지막값이라 같은 이벤트를 다시 받아도
 * 결과가 같고, 거래량은 증분 합이 아니라 <i>누적 거래량의 차이</i>로 계산하기 때문이다.
 *
 * <p>market-ingest(한 대)에서만 돈다 (§66.2). 봉을 확정해 DB에 쓰는 곳은 하나여야 한다.
 * 진행 중인 봉은 갱신할 때마다 {@link CurrentCandleStore}(Valkey)에도 써서 market-service(api)가 읽게 한다 (§66.4).
 */
@Component
public class CandleAggregator {

    private static final Logger log = LoggerFactory.getLogger(CandleAggregator.class);

    private final CandleRepository candles;
    private final CurrentCandleStore current;
    private final Clock clock;
    private final Map<String, Bucket> inProgress = new ConcurrentHashMap<>();

    public CandleAggregator(CandleRepository candles, CurrentCandleStore current, Clock clock) {
        this.candles = candles;
        this.current = current;
        this.clock = clock;
    }

    public void accept(String symbol, BigDecimal price, long cumulativeVolume, Instant timestamp) {
        Instant minute = timestamp.truncatedTo(ChronoUnit.MINUTES);

        Bucket updated = inProgress.compute(symbol, (key, bucket) -> {
            if (bucket == null) {
                return Bucket.open(minute, price, cumulativeVolume);
            }
            if (minute.isAfter(bucket.openTime)) {
                persist(symbol, bucket);
                // 새 봉의 거래량 기준점은 직전 봉의 마지막 누적값이다.
                return Bucket.openFrom(minute, price, cumulativeVolume, bucket.lastCumulativeVolume);
            }
            if (minute.isBefore(bucket.openTime)) {
                // 이미 확정된 분의 지각 이벤트. 되돌리지 않는다.
                return bucket;
            }
            bucket.update(price, cumulativeVolume);
            return bucket;
        });
        // compute 밖에서 쓴다. 맵 잠금을 잡은 채 네트워크를 타지 않는다. 값은 이 순간의 스냅샷이다.
        current.save(updated.toCandle(symbol));
    }

    /** 아직 확정되지 않은 현재 봉. 차트의 마지막 봉으로 쓴다. */
    public Optional<Candle> currentCandle(String symbol) {
        Bucket bucket = inProgress.get(symbol);
        return bucket == null ? Optional.empty() : Optional.of(bucket.toCandle(symbol));
    }

    /**
     * 시세가 끊긴 종목의 봉을 확정한다.
     *
     * <p>봉은 보통 다음 틱이 들어올 때 확정된다. 종목이 조용해지면 그 트리거가 없으므로
     * 주기적으로 훑어 지난 분의 봉을 닫는다.
     */
    @Scheduled(fixedDelayString = "${market.candle.sweep-interval-ms:30000}")
    public void closeStaleCandles() {
        Instant currentMinute = Instant.now(clock).truncatedTo(ChronoUnit.MINUTES);
        for (String symbol : List.copyOf(inProgress.keySet())) {
            inProgress.computeIfPresent(symbol, (key, bucket) -> {
                if (bucket.openTime.isBefore(currentMinute)) {
                    persist(symbol, bucket);
                    return null;
                }
                return bucket;
            });
        }
    }

    @PreDestroy
    void flushOnShutdown() {
        for (String symbol : List.copyOf(inProgress.keySet())) {
            inProgress.computeIfPresent(symbol, (key, bucket) -> {
                persist(symbol, bucket);
                return null;
            });
        }
    }

    private void persist(String symbol, Bucket bucket) {
        try {
            candles.save(bucket.toCandle(symbol));
        } catch (RuntimeException e) {
            // 봉 저장 실패로 시세 소비가 멈추면 안 된다. 다음 봉은 계속 만들어진다.
            log.error("1분봉 저장 실패: symbol={} openTime={}", symbol, bucket.openTime, e);
        }
    }

    /** 집계 중인 봉. {@code inProgress} 맵의 compute 블록 안에서만 변경된다. */
    private static final class Bucket {

        private final Instant openTime;
        private final BigDecimal open;
        private final long baseCumulativeVolume;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private long lastCumulativeVolume;

        private Bucket(Instant openTime, BigDecimal price, long cumulativeVolume, long baseCumulativeVolume) {
            this.openTime = openTime;
            this.open = price;
            this.high = price;
            this.low = price;
            this.close = price;
            this.baseCumulativeVolume = baseCumulativeVolume;
            this.lastCumulativeVolume = cumulativeVolume;
        }

        /** 첫 봉. 이전 누적 거래량을 알 수 없어 이 시점의 값을 기준으로 삼는다. */
        static Bucket open(Instant openTime, BigDecimal price, long cumulativeVolume) {
            return new Bucket(openTime, price, cumulativeVolume, cumulativeVolume);
        }

        static Bucket openFrom(Instant openTime, BigDecimal price, long cumulativeVolume, long base) {
            return new Bucket(openTime, price, cumulativeVolume, base);
        }

        void update(BigDecimal price, long cumulativeVolume) {
            this.high = high.max(price);
            this.low = low.min(price);
            this.close = price;
            this.lastCumulativeVolume = Math.max(this.lastCumulativeVolume, cumulativeVolume);
        }

        Candle toCandle(String symbol) {
            return new Candle(symbol, openTime, open, high, low, close,
                    Math.max(0L, lastCumulativeVolume - baseCumulativeVolume));
        }
    }
}
