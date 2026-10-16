package com.team.wts.market.quote.domain;

/**
 * 시세 공급자가 밀어 넣는 데이터를 받는 포트.
 *
 * <p>{@link MarketDataProvider}가 조회(pull) 방향이라면 이쪽은 수신(push) 방향이다.
 * KIS WebSocket이든 Mock 스트림이든 여기로 들어오고, 이후 처리는 동일하다.
 */
public interface MarketDataListener {

    void onPrice(MarketPrice price);

    void onOrderBook(OrderBook orderBook);
}
