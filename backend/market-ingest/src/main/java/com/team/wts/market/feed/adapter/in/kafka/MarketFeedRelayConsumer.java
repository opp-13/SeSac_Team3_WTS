package com.team.wts.market.feed.adapter.in.kafka;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.team.wts.common.event.KafkaTopics;
import com.team.wts.market.feed.application.MarketFeedRelay;

/**
 * {@code market.price.updated}를 묶음으로 소비해 corebank로 중계한다. (CLAUDE.md §65.3)
 *
 * <p>캔들 집계와 다른 컨슈머 그룹이다. 중계가 밀리거나 corebank가 꺼져 있어도 캔들은 영향받지 않는다.
 *
 * <p>묶음 크기는 fetch 설정으로 정한다. 브로커가 64KB가 차거나 300ms가 지나면 돌려준다.
 * 시세가 뜸하면 한 건씩, 몰리면 300ms 안의 것을 한 번에 받는다. 타이머를 따로 두지 않는다.
 * 중계는 예외를 밖으로 던지지 않으므로 묶음마다 오프셋이 넘어간다 (실패한 묶음은 버린다).
 */
@Component
@ConditionalOnProperty(prefix = "market.feed-relay", name = "enabled", havingValue = "true")
public class MarketFeedRelayConsumer {

    private final MarketFeedRelay relay;

    public MarketFeedRelayConsumer(MarketFeedRelay relay) {
        this.relay = relay;
    }

    @KafkaListener(
            topics = KafkaTopics.MARKET_PRICE_UPDATED,
            groupId = "${market.feed-relay.consumer-group:market-service-feed-relay}",
            batch = "true",
            properties = {
                "fetch.min.bytes=65536",
                "fetch.max.wait.ms=300",
                "max.poll.records=500",
            })
    public void onPrices(List<String> messages) {
        relay.relay(messages);
    }
}
