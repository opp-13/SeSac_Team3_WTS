package com.team.wts.market.quote.adapter.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.socket.CloseStatus;

import com.team.wts.market.config.MarketWebSocketProperties;
import com.team.wts.market.quote.adapter.ws.dto.ServerMessages;

/** 종료할 때 WebSocket을 끊김 없이 넘긴다. (CLAUDE.md §68) */
class WebSocketShutdownTest {

    private final MarketSessionRegistry registry = mock(MarketSessionRegistry.class);

    private WebSocketShutdown shutdown(Duration drainTimeout) {
        return new WebSocketShutdown(registry, new MarketWebSocketProperties(drainTimeout));
    }

    @Test
    @DisplayName("DRAIN을 보내고, 클라이언트가 모두 옮겨 가면 기다리지 않고 끝낸다")
    void drainsAndReturnsEarlyWhenClientsLeave() {
        when(registry.sessionCount()).thenReturn(2, 0);
        WebSocketShutdown shutdown = shutdown(Duration.ofSeconds(5));

        long started = System.nanoTime();
        shutdown.stop();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        InOrder order = inOrder(registry);
        order.verify(registry).sendToAll(ServerMessages.Drain.INSTANCE);
        order.verify(registry).closeAll(WebSocketShutdown.GOING_AWAY);
        assertThat(elapsedMillis).isLessThan(1_000);
    }

    @Test
    @DisplayName("옮겨 가지 않은 세션은 drain-timeout 뒤 1001로 닫는다")
    void closesRemainingAfterTimeout() {
        when(registry.sessionCount()).thenReturn(1);
        WebSocketShutdown shutdown = shutdown(Duration.ofMillis(300));

        long started = System.nanoTime();
        shutdown.stop();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        verify(registry).sendToAll(ServerMessages.Drain.INSTANCE);
        verify(registry).closeAll(WebSocketShutdown.GOING_AWAY);
        assertThat(elapsedMillis).isBetween(250L, 2_000L);
        assertThat(WebSocketShutdown.GOING_AWAY.getCode()).isEqualTo(CloseStatus.GOING_AWAY.getCode());
    }

    @Test
    @DisplayName("세션이 없으면 DRAIN도 대기도 없다")
    void skipsDrainWithoutSessions() {
        when(registry.sessionCount()).thenReturn(0);

        shutdown(Duration.ofSeconds(5)).stop();

        verify(registry, never()).sendToAll(any());
    }

    @Test
    @DisplayName("웹 서버 graceful shutdown보다 먼저 멈추는 phase다")
    void stopsBeforeWebServerGracefulShutdown() {
        // WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE = DEFAULT_PHASE - 1024. 높은 phase가 먼저 멈춘다.
        assertThat(shutdown(Duration.ofSeconds(5)).getPhase()).isGreaterThan(SmartLifecycle.DEFAULT_PHASE - 1024);
    }
}
