package com.team.wts.trading.market.adapter.out.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.team.wts.common.event.KafkaTopics;

/**
 * channel에서 중계해 온 시세 이벤트를 이 tier의 Kafka에 다시 낸다. (CLAUDE.md §65.3)
 *
 * <p>받은 봉투를 그대로 낸다. eventId · traceId가 channel의 원래 이벤트와 같다.
 * 키는 종목코드다 (§58.4). 소비자는 지금처럼 {@code MarketPriceUpdatedConsumer}와 Phase 8 엔진이다.
 *
 * <p>Outbox를 거치지 않는다 (ADR-0021 5항). 거래 DB의 변경이 아니고, 잃어도 다음 시세가 덮는다.
 */
@Component
@ConditionalOnProperty(prefix = "trading.market-feed", name = "enabled", havingValue = "true")
public class KafkaMarketPriceRepublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaMarketPriceRepublisher.class);

    private final KafkaTemplate<String, String> kafka;

    public KafkaMarketPriceRepublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    public void publish(String symbol, String eventId, String eventJson) {
        kafka.send(KafkaTopics.MARKET_PRICE_UPDATED, symbol, eventJson)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.warn("중계 시세 발행 실패: symbol={} eventId={} ({})",
                                symbol, eventId, error.getMessage());
                    }
                });
    }
}
