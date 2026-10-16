package com.team.wts.market.candle.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.team.wts.market.candle.domain.Candle;
import com.team.wts.market.candle.domain.CurrentCandleStore;
import com.team.wts.market.candle.domain.CandleRepository;

/** 1분봉 집계. (CLAUDE.md §22) */
class CandleAggregatorTest {

    private static final String SYMBOL = "005930";
    private static final Instant MINUTE_1 = Instant.parse("2026-09-21T01:30:00Z");
    private static final Instant MINUTE_2 = Instant.parse("2026-09-21T01:31:00Z");

    private InMemoryCandleRepository repository;
    private InMemoryCurrentCandleStore currentStore;
    private CandleAggregator aggregator;

    @BeforeEach
    void setUp() {
        repository = new InMemoryCandleRepository();
        currentStore = new InMemoryCurrentCandleStore();
        aggregator = new CandleAggregator(repository, currentStore,
                Clock.fixed(MINUTE_2.plusSeconds(30), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("진행 중인 봉을 갱신할 때마다 공유 저장소(Valkey)에도 쓴다 - api 인스턴스가 읽는다 (§66.4)")
    void writesInProgressCandleToSharedStore() {
        aggregator.accept("005930", new java.math.BigDecimal("80000"), 100, MINUTE_2.plusSeconds(1));
        aggregator.accept("005930", new java.math.BigDecimal("80500"), 130, MINUTE_2.plusSeconds(20));

        Candle shared = currentStore.find("005930").orElseThrow();
        assertThat(shared.openTime()).isEqualTo(MINUTE_2);
        assertThat(shared.high()).isEqualByComparingTo("80500");
        assertThat(shared.close()).isEqualByComparingTo("80500");
        assertThat(shared.volume()).isEqualTo(30);
    }

    private static final class InMemoryCurrentCandleStore implements CurrentCandleStore {

        private final java.util.Map<String, Candle> values = new java.util.HashMap<>();

        @Override
        public void save(Candle current) {
            values.put(current.symbol(), current);
        }

        @Override
        public java.util.Optional<Candle> find(String symbol) {
            return java.util.Optional.ofNullable(values.get(symbol));
        }
    }

    @Test
    @DisplayName("같은 분의 시세는 하나의 봉으로 모인다")
    void aggregatesTicksWithinSameMinute() {
        accept("80000", 1000, MINUTE_1.plusSeconds(1));
        accept("80500", 1500, MINUTE_1.plusSeconds(20));
        accept("79800", 2000, MINUTE_1.plusSeconds(40));

        Candle current = aggregator.currentCandle(SYMBOL).orElseThrow();
        assertThat(current.openTime()).isEqualTo(MINUTE_1);
        assertThat(current.open()).isEqualByComparingTo("80000");
        assertThat(current.high()).isEqualByComparingTo("80500");
        assertThat(current.low()).isEqualByComparingTo("79800");
        assertThat(current.close()).isEqualByComparingTo("79800");
    }

    @Test
    @DisplayName("분이 바뀌면 직전 봉을 확정 저장하고 새 봉을 연다")
    void closesPreviousCandleOnMinuteRollover() {
        accept("80000", 1000, MINUTE_1.plusSeconds(1));
        accept("80500", 1500, MINUTE_1.plusSeconds(40));
        accept("80600", 1800, MINUTE_2.plusSeconds(1));

        assertThat(repository.saved).hasSize(1);
        Candle closed = repository.saved.get(0);
        assertThat(closed.openTime()).isEqualTo(MINUTE_1);
        assertThat(closed.close()).isEqualByComparingTo("80500");

        assertThat(aggregator.currentCandle(SYMBOL).orElseThrow().openTime()).isEqualTo(MINUTE_2);
    }

    @Test
    @DisplayName("거래량은 누적 거래량의 차이로 계산한다")
    void derivesVolumeFromCumulativeDifference() {
        accept("80000", 1000, MINUTE_1.plusSeconds(1));
        accept("80100", 1700, MINUTE_1.plusSeconds(30));
        // 분이 바뀌며 1분봉 확정. 다음 봉의 기준점은 직전 봉의 마지막 누적값(1700)이다.
        accept("80200", 2500, MINUTE_2.plusSeconds(1));

        assertThat(repository.saved.get(0).volume()).isEqualTo(700L);   // 1700 - 1000
        assertThat(aggregator.currentCandle(SYMBOL).orElseThrow().volume()).isEqualTo(800L); // 2500 - 1700
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받아도 결과가 달라지지 않는다")
    void isIdempotentForDuplicatedEvents() {
        accept("80000", 1000, MINUTE_1.plusSeconds(1));
        accept("80500", 1500, MINUTE_1.plusSeconds(20));
        Candle once = aggregator.currentCandle(SYMBOL).orElseThrow();

        accept("80500", 1500, MINUTE_1.plusSeconds(20));
        Candle twice = aggregator.currentCandle(SYMBOL).orElseThrow();

        assertThat(twice.open()).isEqualByComparingTo(once.open());
        assertThat(twice.high()).isEqualByComparingTo(once.high());
        assertThat(twice.low()).isEqualByComparingTo(once.low());
        assertThat(twice.close()).isEqualByComparingTo(once.close());
        assertThat(twice.volume()).isEqualTo(once.volume());
    }

    @Test
    @DisplayName("이미 확정된 분의 지각 이벤트는 현재 봉을 되돌리지 않는다")
    void ignoresLateEventForClosedMinute() {
        accept("80000", 1000, MINUTE_1.plusSeconds(1));
        accept("80600", 1800, MINUTE_2.plusSeconds(1));

        accept("70000", 1200, MINUTE_1.plusSeconds(50));

        Candle current = aggregator.currentCandle(SYMBOL).orElseThrow();
        assertThat(current.openTime()).isEqualTo(MINUTE_2);
        assertThat(current.low()).isEqualByComparingTo("80600");
    }

    @Test
    @DisplayName("시세가 끊긴 종목의 봉은 주기적으로 닫힌다")
    void closesStaleCandleWithoutFurtherTicks() {
        accept("80000", 1000, MINUTE_1.plusSeconds(1));

        // Clock은 MINUTE_2 안에 있으므로 MINUTE_1 봉은 이미 지난 분이다.
        aggregator.closeStaleCandles();

        assertThat(repository.saved).hasSize(1);
        assertThat(repository.saved.get(0).openTime()).isEqualTo(MINUTE_1);
        assertThat(aggregator.currentCandle(SYMBOL)).isEmpty();
    }

    @Test
    @DisplayName("저장이 실패해도 집계는 계속된다")
    void keepsAggregatingWhenPersistenceFails() {
        repository.failOnSave = true;

        accept("80000", 1000, MINUTE_1.plusSeconds(1));
        accept("80600", 1800, MINUTE_2.plusSeconds(1));

        assertThat(aggregator.currentCandle(SYMBOL).orElseThrow().openTime()).isEqualTo(MINUTE_2);
    }

    private void accept(String price, long cumulativeVolume, Instant at) {
        aggregator.accept(SYMBOL, new BigDecimal(price), cumulativeVolume, at);
    }

    private static final class InMemoryCandleRepository implements CandleRepository {

        private final List<Candle> saved = new ArrayList<>();
        private final Map<String, Candle> byKey = new LinkedHashMap<>();
        private boolean failOnSave;

        @Override
        public Candle save(Candle candle) {
            if (failOnSave) {
                throw new IllegalStateException("저장 실패 시뮬레이션");
            }
            saved.add(candle);
            byKey.put(candle.symbol() + candle.openTime(), candle);
            return candle;
        }

        @Override
        public List<Candle> findBySymbolAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                String symbol, Instant from) {
            return byKey.values().stream()
                    .filter(candle -> candle.symbol().equals(symbol))
                    .filter(candle -> !candle.openTime().isBefore(from))
                    .sorted(java.util.Comparator.comparing(Candle::openTime))
                    .toList();
        }
    }
}
