package com.team.wts.market.quote.adapter.ws.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;
import com.team.wts.market.quote.domain.QuoteSource;

/**
 * 서버가 보내는 메시지. (CLAUDE.md §25)
 *
 * <p>timestamp는 UTC ISO-8601로 내보낸다. 표시용 Asia/Seoul 변환은 프론트엔드가 한다 (§43).
 */
public final class ServerMessages {

    private ServerMessages() {
    }

    public record Price(
            String type,
            String symbol,
            BigDecimal price,
            BigDecimal change,
            BigDecimal changeRate,
            long volume,
            Instant timestamp,
            QuoteSource source) {

        public static Price from(MarketPrice source) {
            return new Price("PRICE", source.symbol(), source.price(),
                    source.change(), source.changeRate(), source.volume(), source.timestamp(), source.source());
        }
    }

    public record Level(BigDecimal price, long quantity) {
    }

    public record Orderbook(
            String type,
            String symbol,
            List<Level> asks,
            List<Level> bids,
            Instant timestamp) {

        public static Orderbook from(OrderBook source) {
            return new Orderbook("ORDERBOOK", source.symbol(),
                    source.asks().stream().map(l -> new Level(l.price(), l.quantity())).toList(),
                    source.bids().stream().map(l -> new Level(l.price(), l.quantity())).toList(),
                    source.timestamp());
        }
    }

    /** 구독 요청 처리 결과. 클라이언트가 현재 구독 상태를 확인할 수 있게 돌려준다. */
    public record Subscribed(String type, List<String> symbols) {

        public static Subscribed of(List<String> symbols) {
            return new Subscribed("SUBSCRIBED", symbols);
        }
    }

    public record Error(String type, String message) {

        public static Error of(String message) {
            return new Error("ERROR", message);
        }
    }
}
