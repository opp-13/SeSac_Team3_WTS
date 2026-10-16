package com.team.wts.market.quote.domain;

import java.util.Optional;

/**
 * 최신 시세 캐시 포트. 구현은 Valkey다. (CLAUDE.md §18)
 *
 * <p>여기 담기는 값은 전부 재생성 가능한 데이터다.
 * 계좌·주문·원장·체결 원본은 절대 넣지 않는다.
 */
public interface QuoteCache {

    void savePrice(MarketPrice price);

    Optional<MarketPrice> findPrice(String symbol);

    void saveOrderBook(OrderBook orderBook);

    Optional<OrderBook> findOrderBook(String symbol);
}
