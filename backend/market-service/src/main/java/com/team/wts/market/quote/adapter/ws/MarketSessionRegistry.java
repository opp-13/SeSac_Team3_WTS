package com.team.wts.market.quote.adapter.ws;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.market.quote.adapter.ws.dto.ServerMessages;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;

/**
 * WebSocket 세션과 종목 구독 상태를 들고 있으면서 실시간 송신을 담당한다. (CLAUDE.md §25)
 *
 * <p>구독하지 않은 종목은 보내지 않는다. 종목이 늘어날수록 이 필터링이 중요해진다.
 *
 * <p>시세 · 호가는 market-ingest가 Valkey pub/sub로 내보내고, 이 인스턴스의 {@code QuoteFanoutSubscriber}가
 * 받아 여기로 넘긴다 (CLAUDE.md §66.3). 그래서 market-service 인스턴스가 몇 개든 각자 자기 세션에 보낸다.
 */
@Component
public class MarketSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(MarketSessionRegistry.class);

    /** 느린 클라이언트 때문에 서버 메모리가 무한정 늘지 않도록 세션별 전송 버퍼를 제한한다. */
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;
    private static final int SEND_TIME_LIMIT_MILLIS = 5_000;

    private final ObjectMapper objectMapper;
    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();

    public MarketSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(WebSocketSession session) {
        // WebSocketSession.sendMessage는 스레드 안전하지 않다.
        // 시세 스레드와 수신 스레드가 동시에 쓸 수 있으므로 반드시 감싼다.
        WebSocketSession concurrent = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_LIMIT_BYTES);
        subscribers.put(session.getId(), new Subscriber(concurrent));
    }

    public void unregister(String sessionId) {
        subscribers.remove(sessionId);
    }

    /** 구독 후의 전체 구독 종목을 돌려준다. */
    public Set<String> subscribe(String sessionId, Iterable<String> symbols) {
        Subscriber subscriber = subscribers.get(sessionId);
        if (subscriber == null) {
            return Set.of();
        }
        symbols.forEach(subscriber.symbols::add);
        return Set.copyOf(subscriber.symbols);
    }

    public Set<String> unsubscribe(String sessionId, Iterable<String> symbols) {
        Subscriber subscriber = subscribers.get(sessionId);
        if (subscriber == null) {
            return Set.of();
        }
        symbols.forEach(subscriber.symbols::remove);
        return Set.copyOf(subscriber.symbols);
    }

    public int sessionCount() {
        return subscribers.size();
    }

    /** 이 인스턴스의 모든 세션이 구독 중인 종목. 시청 수요 하트비트가 쓴다 (§67). */
    public Set<String> subscribedSymbols() {
        Set<String> symbols = new HashSet<>();
        subscribers.values().forEach(subscriber -> symbols.addAll(subscriber.symbols));
        return symbols;
    }

    // ── 송신 (QuoteFanoutSubscriber가 부른다) ────────────────

    public void broadcastPrice(MarketPrice price) {
        send(price.symbol(), ServerMessages.Price.from(price));
    }

    public void broadcastOrderBook(OrderBook orderBook) {
        send(orderBook.symbol(), ServerMessages.Orderbook.from(orderBook));
    }

    /** 특정 세션 하나에만 보낸다. 구독 직후 스냅샷 전송에 쓴다. */
    public void sendTo(String sessionId, Object payload) {
        Subscriber subscriber = subscribers.get(sessionId);
        if (subscriber != null) {
            write(subscriber, payload);
        }
    }

    private void send(String symbol, Object payload) {
        if (subscribers.isEmpty()) {
            return;
        }
        String json = serialize(payload);
        if (json == null) {
            return;
        }
        for (Subscriber subscriber : subscribers.values()) {
            if (subscriber.symbols.contains(symbol)) {
                write(subscriber, json);
            }
        }
    }

    private void write(Subscriber subscriber, Object payload) {
        String json = payload instanceof String text ? text : serialize(payload);
        if (json == null) {
            return;
        }
        if (!subscriber.session.isOpen()) {
            unregister(subscriber.session.getId());
            return;
        }
        try {
            subscriber.session.sendMessage(new TextMessage(json));
        } catch (IOException | IllegalStateException e) {
            // 끊긴 세션이거나 전송 버퍼가 넘친 경우다. 해당 세션만 정리하고 나머지는 계속 보낸다.
            log.debug("WebSocket 전송 실패, 세션 정리: sessionId={} ({})",
                    subscriber.session.getId(), e.getMessage());
            unregister(subscriber.session.getId());
            closeQuietly(subscriber.session);
        }
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("WebSocket 메시지 직렬화 실패: {}", payload.getClass().getSimpleName(), e);
            return null;
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException e) {
            log.debug("WebSocket 세션 종료 실패: {}", e.getMessage());
        }
    }

    private static final class Subscriber {

        private final WebSocketSession session;
        private final Set<String> symbols = ConcurrentHashMap.newKeySet();

        private Subscriber(WebSocketSession session) {
            this.session = session;
        }
    }
}
