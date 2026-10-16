package com.team.wts.market.quote.domain;

/** 시세 이벤트 발행 포트. 구현은 Kafka다. (CLAUDE.md §16) */
public interface MarketEventPublisher {

    void publishPriceUpdated(MarketPrice price);
}
