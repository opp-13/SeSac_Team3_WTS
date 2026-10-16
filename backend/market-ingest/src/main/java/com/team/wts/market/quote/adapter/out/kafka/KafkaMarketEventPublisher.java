package com.team.wts.market.quote.adapter.out.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.team.wts.common.event.DomainEvent;
import com.team.wts.common.event.KafkaTopics;
import com.team.wts.common.web.TraceIdFilter;
import com.team.wts.market.quote.domain.MarketEventPublisher;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.event.MarketPriceUpdated;

/**
 * 시세 이벤트를 Kafka로 발행한다. (CLAUDE.md §16)
 *
 * <p>메시지 키를 종목코드로 둔다. 같은 종목의 이벤트가 항상 같은 파티션으로 가야
 * 소비 측(캔들 집계)에서 순서가 보장된다.
 */
@Component
public class KafkaMarketEventPublisher implements MarketEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaMarketEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaMarketEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishPriceUpdated(MarketPrice price) {
        DomainEvent<MarketPriceUpdated> event = DomainEvent.of(
                KafkaTopics.MARKET_PRICE_UPDATED,
                price.symbol(),
                MDC.get(TraceIdFilter.MDC_KEY),
                MarketPriceUpdated.from(price));

        kafkaTemplate.send(KafkaTopics.MARKET_PRICE_UPDATED, price.symbol(), event)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        // 시세는 재생성 가능한 데이터다. 발행 실패로 스트림을 멈추지 않는다.
                        // 다음 틱이 최신 값을 다시 싣고 간다.
                        log.warn("시세 이벤트 발행 실패: symbol={} eventId={}",
                                price.symbol(), event.eventId(), error);
                    }
                });
    }
}
