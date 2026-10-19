package com.team.wts.market.quote.adapter.ws.dto;

import java.util.List;

/**
 * 클라이언트가 보내는 메시지. (CLAUDE.md §25)
 *
 * <pre>
 * { "type": "SUBSCRIBE",   "symbols": ["005930", "000660"] }
 * { "type": "UNSUBSCRIBE", "symbols": ["005930"] }
 * { "type": "PING" }      유휴 연결 유지 (§68). 서버는 PONG으로 답한다
 * </pre>
 */
public record ClientCommand(String type, List<String> symbols) {

    public static final String SUBSCRIBE = "SUBSCRIBE";
    public static final String UNSUBSCRIBE = "UNSUBSCRIBE";
    public static final String PING = "PING";

    public ClientCommand {
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }
}
