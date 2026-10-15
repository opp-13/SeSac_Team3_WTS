package com.team.wts.trading.market.adapter.out.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * channel에서 중계해 온 최신 시세를 이 tier의 Valkey에 쓴다. (CLAUDE.md §65.3)
 *
 * <p>값은 market-service {@code ValkeyQuoteCache}가 쓰는 것과 같은 JSON이다. 그래서
 * {@link ValkeyMarketPriceQuery}가 읽는 방법은 바뀌지 않는다 (ADR-0008).
 *
 * <p>쓰기 실패는 로그만 남긴다. 시세는 다시 온다 (§18).
 */
@Component
@ConditionalOnProperty(prefix = "trading.market-feed", name = "enabled", havingValue = "true")
public class ValkeyMarketPriceMirror {

    private static final Logger log = LoggerFactory.getLogger(ValkeyMarketPriceMirror.class);

    /** {@link ValkeyMarketPriceQuery}와 market-service {@code ValkeyQuoteCache}의 키 접두사. */
    private static final String PRICE_KEY_PREFIX = "market:price:";

    private final StringRedisTemplate redis;

    public ValkeyMarketPriceMirror(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return 썼으면 true */
    public boolean save(String symbol, String priceJson) {
        try {
            redis.opsForValue().set(PRICE_KEY_PREFIX + symbol, priceJson);
            return true;
        } catch (RedisConnectionFailureException e) {
            log.warn("Valkey 쓰기 실패: symbol={} ({})", symbol, e.getMessage());
            return false;
        }
    }
}
