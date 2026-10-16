package com.team.wts.market.candle.adapter.in.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.common.event.DomainEvent;
import com.team.wts.common.event.KafkaTopics;
import com.team.wts.market.candle.application.CandleAggregator;
import com.team.wts.market.quote.event.MarketPriceUpdated;

/**
 * {@code market.price.updated}를 소비해 1분봉을 만든다. (CLAUDE.md §22)
 *
 * <p>메시지를 문자열로 받아 직접 역직렬화한다. Kafka 타입 헤더에 의존하면
 * 발행 측 클래스 이름이 소비 측 계약이 되어버리기 때문이다.
 * 다른 서비스가 같은 토픽을 소비할 때(Phase 3) 각자의 payload 클래스를 쓸 수 있어야 한다.
 */
@Component
public class MarketPriceUpdatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(MarketPriceUpdatedConsumer.class);

    private static final TypeReference<DomainEvent<MarketPriceUpdated>> EVENT_TYPE =
            new TypeReference<>() { };

    private final CandleAggregator aggregator;
    private final ObjectMapper objectMapper;

    public MarketPriceUpdatedConsumer(CandleAggregator aggregator, ObjectMapper objectMapper) {
        this.aggregator = aggregator;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = KafkaTopics.MARKET_PRICE_UPDATED,
            groupId = "${market.candle.consumer-group:market-service-candle}")
    public void onPriceUpdated(String message) {
        DomainEvent<MarketPriceUpdated> event;
        try {
            event = objectMapper.readValue(message, EVENT_TYPE);
        } catch (Exception e) {
            // 형식이 깨진 메시지는 재시도해도 같은 결과다. 로그로 남기고 넘어간다.
            // DLQ는 MVP 후반에 붙인다 (CLAUDE.md §17).
            log.error("시세 이벤트 파싱 실패, 건너뜀: {}", message, e);
            return;
        }

        MarketPriceUpdated payload = event.payload();
        aggregator.accept(payload.symbol(), payload.price(), payload.volume(), payload.timestamp());
    }
}
