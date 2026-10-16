package com.team.wts.market.quote.domain;

import java.time.Instant;
import java.util.List;

/**
 * 호가. (CLAUDE.md §25 – Server Orderbook)
 *
 * @param asks 매도호가. 최우선(가장 낮은 가격)부터 오름차순.
 * @param bids 매수호가. 최우선(가장 높은 가격)부터 내림차순.
 */
public record OrderBook(
        String symbol,
        List<OrderBookLevel> asks,
        List<OrderBookLevel> bids,
        Instant timestamp) {

    public OrderBook {
        asks = List.copyOf(asks);
        bids = List.copyOf(bids);
    }
}
