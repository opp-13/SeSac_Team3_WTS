package com.team.wts.trading.market.adapter.out.cache;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * channel에서 중계해 온 최신 시세를 이 tier의 Valkey에 쓴다. (CLAUDE.md §65.3, §66.5)
 *
 * <p>값은 market-service {@code ValkeyQuoteCache}가 쓰는 것과 같은 JSON이다. 그래서
 * {@link ValkeyMarketPriceQuery}가 읽는 방법은 바뀌지 않는다 (ADR-0008).
 *
 * <p><b>더 새로운 시세만 쓴다. 비교와 쓰기는 Lua 스크립트 하나로 원자적이다.</b> trading-service가 여러 개(HPA)면
 * 같은 종목의 요청이 동시에 다른 파드로 갈 수 있다. 읽고 비교한 뒤 쓰면 늦게 도착한 옛 시세가 새 시세를 덮는다.
 * 비교 기준은 보조 키 {@code market:price:seq:{symbol}}의 epoch 마이크로초다. 값 JSON의 ISO 문자열은
 * 소수 자릿수가 달라 문자열로 비교할 수 없다.
 *
 * <p>두 키는 단일 노드 Valkey를 전제한다(tier마다 하나, §64.2). 클러스터 모드로 바꾸면 해시 태그가 필요하다.
 */
@Component
@ConditionalOnProperty(prefix = "trading.market-feed", name = "enabled", havingValue = "true")
public class ValkeyMarketPriceMirror {

    private static final Logger log = LoggerFactory.getLogger(ValkeyMarketPriceMirror.class);

    /** {@link ValkeyMarketPriceQuery}와 market-service {@code ValkeyQuoteCache}의 키 접두사. */
    private static final String PRICE_KEY_PREFIX = "market:price:";
    private static final String SEQ_KEY_PREFIX = "market:price:seq:";

    /** KEYS[1] 값 키, KEYS[2] 순번 키, ARGV[1] epoch 마이크로초, ARGV[2] 값 JSON. 썼으면 1, 더 새롭지 않으면 0. */
    private static final RedisScript<Long> SAVE_IF_NEWER = RedisScript.of("""
            local current = tonumber(redis.call('GET', KEYS[2]) or '0')
            if tonumber(ARGV[1]) <= current then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[2])
            redis.call('SET', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public ValkeyMarketPriceMirror(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * {@code timestamp}가 지금 가진 값보다 새로울 때만 쓴다.
     */
    public SaveResult saveIfNewer(String symbol, Instant timestamp, String priceJson) {
        // epoch 마이크로초는 Lua의 숫자(double)로 정확히 표현된다 (2^53 > 1.8e15).
        long micros = Math.addExact(Math.multiplyExact(timestamp.getEpochSecond(), 1_000_000L),
                timestamp.getNano() / 1_000L);
        try {
            Long written = redis.execute(SAVE_IF_NEWER,
                    List.of(PRICE_KEY_PREFIX + symbol, SEQ_KEY_PREFIX + symbol),
                    Long.toString(micros), priceJson);
            return written != null && written == 1L ? SaveResult.WRITTEN : SaveResult.NOT_NEWER;
        } catch (DataAccessException e) {
            log.warn("Valkey 쓰기 실패: symbol={} ({})", symbol, e.getMessage());
            return SaveResult.FAILED;
        }
    }

    public enum SaveResult { WRITTEN, NOT_NEWER, FAILED }
}
