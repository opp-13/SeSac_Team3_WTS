package com.team.wts.market.quote.domain;

/**
 * 구독 중인 WTS 클라이언트로 실시간 송신하는 포트. (CLAUDE.md §6.2, §25)
 *
 * <p>구현은 Valkey pub/sub로 내보내고, market-service(api) 인스턴스가 모두 받아 자기 세션에 보낸다
 * (CLAUDE.md §66.3, §18 "WebSocket fan-out 보조"). 수집하는 앱(market-ingest)과 송신하는 앱이 다르다.
 */
public interface QuoteBroadcaster {

    void broadcastPrice(MarketPrice price);

    void broadcastOrderBook(OrderBook orderBook);
}
