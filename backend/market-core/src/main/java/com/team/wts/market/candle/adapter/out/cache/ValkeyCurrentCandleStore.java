package com.team.wts.market.candle.adapter.out.cache;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.market.candle.domain.Candle;
import com.team.wts.market.candle.domain.CurrentCandleStore;

/**
 * 현재 1분봉을 Valkey {@code market:candle:1m:{symbol}}에 둔다. (CLAUDE.md §66.4, §18)
 *
 * <p>TTL을 건다. market-ingest가 멈추면 오래된 "현재 봉"이 차트 끝에 계속 남지 않게 한다.
 * 쓰기 · 읽기 실패는 로그만 남긴다. 조회는 확정 봉만으로 응답한다.
 */
@Component
public class ValkeyCurrentCandleStore implements CurrentCandleStore {

    private static final Logger log = LoggerFactory.getLogger(ValkeyCurrentCandleStore.class);

    private static final String KEY_PREFIX = "market:candle:1m:";
    /** 한 봉(1분)보다 넉넉히. 확정된 봉은 DB에서 읽고, 같은 시각의 현재 봉은 조회가 걸러 낸다. */
    private static final Duration TTL = Duration.ofMinutes(3);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ValkeyCurrentCandleStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(Candle current) {
        String key = KEY_PREFIX + current.symbol();
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(CandleValue.from(current)), TTL);
        } catch (JsonProcessingException e) {
            log.error("현재 봉 직렬화 실패: key={}", key, e);
        } catch (DataAccessException e) {
            log.warn("Valkey 쓰기 실패: key={} ({})", key, e.getMessage());
        }
    }

    @Override
    public Optional<Candle> find(String symbol) {
        String key = KEY_PREFIX + symbol;
        try {
            String json = redis.opsForValue().get(key);
            return json == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(json, CandleValue.class).toCandle());
        } catch (JsonProcessingException e) {
            // 형식이 바뀐 낡은 값이다. 다음 틱이 덮어쓴다.
            log.error("현재 봉 역직렬화 실패: key={}", key, e);
            return Optional.empty();
        } catch (DataAccessException e) {
            log.warn("Valkey 읽기 실패: key={} ({})", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** Valkey 값. {@link Candle}은 JPA 엔티티라 그대로 직렬화하지 않는다. */
    record CandleValue(String symbol, Instant openTime, BigDecimal open, BigDecimal high,
            BigDecimal low, BigDecimal close, long volume) {

        static CandleValue from(Candle candle) {
            return new CandleValue(candle.symbol(), candle.openTime(), candle.open(), candle.high(),
                    candle.low(), candle.close(), candle.volume());
        }

        Candle toCandle() {
            return new Candle(symbol, openTime, open, high, low, close, volume);
        }
    }
}
