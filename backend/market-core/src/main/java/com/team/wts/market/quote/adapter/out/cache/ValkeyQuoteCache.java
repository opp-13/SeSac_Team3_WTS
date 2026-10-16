package com.team.wts.market.quote.adapter.out.cache;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;
import com.team.wts.market.quote.domain.QuoteCache;

/**
 * 최신 시세 캐시. (CLAUDE.md §18)
 *
 * <pre>
 * market:price:005930
 * market:orderbook:005930
 * </pre>
 *
 * <p>TTL을 걸지 않는다. "최신 시세"는 장 마감 후에도 마지막 값이 읽혀야 하고,
 * 값이 오래됐는지는 {@link MarketPrice#isStale} 로 timestamp를 보고 판단한다 (§11).
 *
 * <p>Valkey 장애가 시세 스트림 전체를 멈추게 하면 안 된다.
 * 여기서 발생한 오류는 로그만 남기고 삼키되, 무엇이 실패했는지는 반드시 남긴다 (§52).
 */
@Component
public class ValkeyQuoteCache implements QuoteCache {

    private static final Logger log = LoggerFactory.getLogger(ValkeyQuoteCache.class);

    private static final String PRICE_KEY_PREFIX = "market:price:";
    private static final String ORDER_BOOK_KEY_PREFIX = "market:orderbook:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ValkeyQuoteCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void savePrice(MarketPrice price) {
        write(PRICE_KEY_PREFIX + price.symbol(), price);
    }

    @Override
    public Optional<MarketPrice> findPrice(String symbol) {
        return read(PRICE_KEY_PREFIX + symbol, MarketPrice.class);
    }

    @Override
    public void saveOrderBook(OrderBook orderBook) {
        write(ORDER_BOOK_KEY_PREFIX + orderBook.symbol(), orderBook);
    }

    @Override
    public Optional<OrderBook> findOrderBook(String symbol) {
        return read(ORDER_BOOK_KEY_PREFIX + symbol, OrderBook.class);
    }

    private void write(String key, Object value) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            log.error("시세 직렬화 실패: key={}", key, e);
        } catch (RedisConnectionFailureException e) {
            log.warn("Valkey 쓰기 실패: key={} ({})", key, e.getMessage());
        }
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            // 형식이 바뀐 낡은 값이 남아 있는 경우다. 다음 틱이 덮어쓴다.
            log.error("시세 역직렬화 실패: key={}", key, e);
            return Optional.empty();
        } catch (RedisConnectionFailureException e) {
            log.warn("Valkey 읽기 실패: key={} ({})", key, e.getMessage());
            return Optional.empty();
        }
    }
}
