package com.team.wts.market.quote.adapter.out.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;
import com.team.wts.market.quote.domain.QuoteBroadcaster;
import com.team.wts.market.quote.fanout.QuoteFanoutChannels;

/**
 * 시세 · 호가를 Valkey pub/sub로 내보낸다. market-service(api) 인스턴스가 모두 받아 자기 WebSocket 세션에 보낸다.
 * (CLAUDE.md §66.3, §18 "WebSocket fan-out 보조")
 *
 * <p>값은 Valkey 캐시({@link ValkeyQuoteCache})와 같은 JSON이다. 저장하지 않는 메시지라 받는 쪽이 없으면 사라진다.
 * 클라이언트는 구독할 때 캐시 스냅샷을 먼저 받는다.
 *
 * <p>발행 실패는 로그만 남긴다. 송신이 수집 경로를 멈추게 하면 안 된다 (§52).
 */
@Component
public class ValkeyQuoteFanoutPublisher implements QuoteBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ValkeyQuoteFanoutPublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ValkeyQuoteFanoutPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void broadcastPrice(MarketPrice price) {
        publish(QuoteFanoutChannels.PRICE, price.symbol(), price);
    }

    @Override
    public void broadcastOrderBook(OrderBook orderBook) {
        publish(QuoteFanoutChannels.ORDER_BOOK, orderBook.symbol(), orderBook);
    }

    private void publish(String channel, String symbol, Object value) {
        try {
            redis.convertAndSend(channel, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            log.error("fan-out 직렬화 실패: channel={} symbol={}", channel, symbol, e);
        } catch (DataAccessException e) {
            log.warn("fan-out 발행 실패: channel={} symbol={} ({})", channel, symbol, e.getMessage());
        }
    }
}
