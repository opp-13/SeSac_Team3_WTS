package com.team.wts.market.quote.adapter.ws;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.market.quote.adapter.ws.dto.ClientCommand;
import com.team.wts.market.quote.adapter.ws.dto.ServerMessages;
import com.team.wts.market.quote.application.QuoteQueryService;
import com.team.wts.market.quote.application.ViewerDemandRecorder;

/**
 * {@code /ws/market} 핸들러. (CLAUDE.md §25)
 *
 * <p>구독 직후에는 캐시에 있는 최신 값을 즉시 한 번 보낸다.
 * 그러지 않으면 다음 틱이 올 때까지 화면이 비어 있다.
 *
 * <p>구독한 종목은 시청 수요로 기록한다. 실제 시세를 받지 않는 종목이면 market-ingest가 다음 틱부터
 * 모의 시세를 만들어 같은 fan-out 경로로 보낸다 (§67).
 */
@Component
public class MarketWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketWebSocketHandler.class);

    private final MarketSessionRegistry registry;
    private final QuoteQueryService quotes;
    private final ViewerDemandRecorder demand;
    private final ObjectMapper objectMapper;

    public MarketWebSocketHandler(MarketSessionRegistry registry,
                                  QuoteQueryService quotes,
                                  ViewerDemandRecorder demand,
                                  ObjectMapper objectMapper) {
        this.registry = registry;
        this.quotes = quotes;
        this.demand = demand;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.register(session);
        log.debug("WebSocket 연결: sessionId={} 총 {}개", session.getId(), registry.sessionCount());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session.getId());
        log.debug("WebSocket 종료: sessionId={} status={}", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientCommand command;
        try {
            command = objectMapper.readValue(message.getPayload(), ClientCommand.class);
        } catch (Exception e) {
            // 클라이언트가 보낸 값이 잘못된 것이지 서버 장애가 아니다. 알려주고 연결은 유지한다.
            registry.sendTo(session.getId(), ServerMessages.Error.of("메시지 형식이 올바르지 않습니다."));
            return;
        }

        String type = command.type() == null ? "" : command.type().toUpperCase();
        switch (type) {
            case ClientCommand.SUBSCRIBE -> handleSubscribe(session, command.symbols());
            case ClientCommand.UNSUBSCRIBE -> {
                Set<String> remaining = registry.unsubscribe(session.getId(), command.symbols());
                registry.sendTo(session.getId(), ServerMessages.Subscribed.of(List.copyOf(remaining)));
            }
            case ClientCommand.PING -> registry.sendTo(session.getId(), ServerMessages.Pong.INSTANCE);
            default -> registry.sendTo(session.getId(),
                    ServerMessages.Error.of("알 수 없는 type 입니다: " + command.type()));
        }
    }

    private void handleSubscribe(WebSocketSession session, List<String> symbols) {
        Set<String> subscribed = registry.subscribe(session.getId(), symbols);
        registry.sendTo(session.getId(), ServerMessages.Subscribed.of(List.copyOf(subscribed)));
        demand.record(symbols);

        for (String symbol : symbols) {
            quotes.findPrice(symbol)
                    .ifPresent(price -> registry.sendTo(session.getId(), ServerMessages.Price.from(price)));
            quotes.findOrderBook(symbol)
                    .ifPresent(book -> registry.sendTo(session.getId(), ServerMessages.Orderbook.from(book)));
        }
    }
}
