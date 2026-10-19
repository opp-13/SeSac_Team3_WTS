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

    /**
     * 이 인스턴스가 곧 내려간다 (CLAUDE.md §68). 클라이언트는 이 연결을 쥔 채 새 연결을 맺고,
     * 새 연결이 구독 확인을 받으면 이 연결을 닫는다. 그래서 계획된 종료에 시세 공백이 없다.
     */
    public record Drain(String type) {

        public static final Drain INSTANCE = new Drain("DRAIN");
    }

    /** PING의 답. 시세가 흐르지 않는 연결도 프록시 · ALB의 유휴 시간 제한에 걸리지 않게 한다 (§68). */
    public record Pong(String type) {

        public static final Pong INSTANCE = new Pong("PONG");
    }

    public record Error(String type, String message) {

        public static Error of(String message) {
            return new Error("ERROR", message);
        }
    }
}
