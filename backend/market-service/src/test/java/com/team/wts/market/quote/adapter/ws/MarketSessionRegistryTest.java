package com.team.wts.market.quote.adapter.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;
import com.team.wts.market.quote.domain.OrderBookLevel;

/** WebSocket 구독 필터링과 세션 정리. (CLAUDE.md §25) */
class MarketSessionRegistryTest {

    private static final Instant NOW = Instant.parse("2026-09-21T01:30:00Z");

    private final MarketSessionRegistry registry =
            new MarketSessionRegistry(Jackson2ObjectMapperBuilder.json().build());

    @Test
    @DisplayName("구독한 종목만 전송한다")
    void sendsOnlySubscribedSymbols() throws IOException {
        WebSocketSession session = openSession("s1");
        registry.register(session);
        registry.subscribe("s1", List.of("005930"));

        registry.broadcastPrice(price("005930"));
        registry.broadcastPrice(price("000660"));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        assertThat(sent.getValue().getPayload()).contains("\"symbol\":\"005930\"");
    }

    @Test
    @DisplayName("구독을 해제하면 더 이상 받지 않는다")
    void stopsSendingAfterUnsubscribe() throws IOException {
        WebSocketSession session = openSession("s1");
        registry.register(session);
        registry.subscribe("s1", List.of("005930"));
        registry.unsubscribe("s1", List.of("005930"));

        registry.broadcastPrice(price("005930"));

        verify(session, never()).sendMessage(any());
    }

    @Test
    @DisplayName("구독 결과로 현재 구독 종목 전체를 돌려준다")
    void returnsCurrentSubscriptionSet() {
        registry.register(openSession("s1"));

        registry.subscribe("s1", List.of("005930"));
        assertThat(registry.subscribe("s1", List.of("000660")))
                .containsExactlyInAnyOrder("005930", "000660");
        assertThat(registry.unsubscribe("s1", List.of("005930"))).containsExactly("000660");
    }

    @Test
    @DisplayName("구독과 상관없이 모든 세션에 보낸다 - 종료 예고(DRAIN, §68)")
    void sendsToEverySessionRegardlessOfSubscription() throws IOException {
        WebSocketSession subscribed = openSession("s1");
        WebSocketSession idle = openSession("s2");
        registry.register(subscribed);
        registry.register(idle);
        registry.subscribe("s1", List.of("005930"));

        registry.sendToAll(com.team.wts.market.quote.adapter.ws.dto.ServerMessages.Drain.INSTANCE);

        ArgumentCaptor<TextMessage> first = ArgumentCaptor.forClass(TextMessage.class);
        ArgumentCaptor<TextMessage> second = ArgumentCaptor.forClass(TextMessage.class);
        verify(subscribed).sendMessage(first.capture());
        verify(idle).sendMessage(second.capture());
        assertThat(first.getValue().getPayload()).isEqualTo("{\"type\":\"DRAIN\"}");
        assertThat(second.getValue().getPayload()).isEqualTo("{\"type\":\"DRAIN\"}");
    }

    @Test
    @DisplayName("종료할 때 모든 세션을 주어진 상태로 닫고 비운다. 한 세션의 실패가 나머지를 막지 않는다")
    void closesEverySessionOnShutdown() throws IOException {
        WebSocketSession broken = openSession("s1");
        WebSocketSession healthy = openSession("s2");
        doThrow(new IOException("이미 끊김")).when(broken).close(any(CloseStatus.class));
        registry.register(broken);
        registry.register(healthy);

        registry.closeAll(CloseStatus.GOING_AWAY);

        verify(broken).close(CloseStatus.GOING_AWAY);
        verify(healthy).close(CloseStatus.GOING_AWAY);
        assertThat(registry.sessionCount()).isZero();
    }

    @Test
    @DisplayName("모든 세션의 구독 종목을 합쳐 돌려준다 - 시청 수요 하트비트용 (§67)")
    void unionsSubscribedSymbolsAcrossSessions() {
        registry.register(openSession("s1"));
        registry.register(openSession("s2"));
        registry.subscribe("s1", List.of("005930", "000660"));
        registry.subscribe("s2", List.of("000660", "000020"));

        assertThat(registry.subscribedSymbols()).containsExactlyInAnyOrder("005930", "000660", "000020");
    }

    @Test
    @DisplayName("세션마다 구독이 독립적이다")
    void keepsSubscriptionsPerSession() throws IOException {
        WebSocketSession samsung = openSession("s1");
        WebSocketSession hynix = openSession("s2");
        registry.register(samsung);
        registry.register(hynix);
        registry.subscribe("s1", List.of("005930"));
        registry.subscribe("s2", List.of("000660"));

        registry.broadcastPrice(price("005930"));

        verify(samsung).sendMessage(any());
        verify(hynix, never()).sendMessage(any());
    }

    @Test
    @DisplayName("전송이 실패한 세션은 정리되고 다른 세션에는 영향을 주지 않는다")
    void removesBrokenSessionAndKeepsOthers() throws IOException {
        WebSocketSession broken = openSession("broken");
        WebSocketSession healthy = openSession("healthy");
        doThrow(new IOException("closed")).when(broken).sendMessage(any());
        registry.register(broken);
        registry.register(healthy);
        registry.subscribe("broken", List.of("005930"));
        registry.subscribe("healthy", List.of("005930"));

        registry.broadcastPrice(price("005930"));

        verify(healthy).sendMessage(any());
        assertThat(registry.sessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("호가도 구독한 세션으로 전송한다")
    void broadcastsOrderBook() throws IOException {
        WebSocketSession session = openSession("s1");
        registry.register(session);
        registry.subscribe("s1", List.of("005930"));

        registry.broadcastOrderBook(new OrderBook("005930",
                List.of(new OrderBookLevel(new BigDecimal("80100"), 10)),
                List.of(new OrderBookLevel(new BigDecimal("79900"), 20)),
                NOW));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        assertThat(sent.getValue().getPayload()).contains("\"type\":\"ORDERBOOK\"");
    }

    @Test
    @DisplayName("연결이 끊긴 세션에는 보내지 않고 목록에서 제거한다")
    void dropsClosedSession() throws IOException {
        WebSocketSession session = openSession("s1");
        when(session.isOpen()).thenReturn(false);
        registry.register(session);
        registry.subscribe("s1", List.of("005930"));

        registry.broadcastPrice(price("005930"));

        verify(session, never()).sendMessage(any());
        assertThat(registry.sessionCount()).isZero();
    }

    private static WebSocketSession openSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static MarketPrice price(String symbol) {
        return new MarketPrice(symbol, new BigDecimal("80000"), new BigDecimal("79000"), 1_000L, NOW);
    }
}
