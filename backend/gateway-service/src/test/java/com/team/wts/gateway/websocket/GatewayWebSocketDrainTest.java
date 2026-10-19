package com.team.wts.gateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

/** gateway 종료 때 WebSocket을 끊김 없이 넘긴다. (CLAUDE.md §68) */
class GatewayWebSocketDrainTest {

    private final WebSocketDrainRegistry registry = mock(WebSocketDrainRegistry.class);

    private GatewayWebSocketDrain drain(Duration timeout) {
        return new GatewayWebSocketDrain(registry, new GatewayWebSocketProperties(timeout));
    }

    @Test
    @DisplayName("DRAIN을 보내고, 클라이언트가 모두 옮겨 가면 기다리지 않고 끝낸다")
    void drainsAndReturnsEarly() {
        when(registry.size()).thenReturn(3, 0);

        long started = System.nanoTime();
        drain(Duration.ofSeconds(5)).stop();

        InOrder order = inOrder(registry);
        order.verify(registry).noticeAll(GatewayWebSocketDrain.DRAIN);
        order.verify(registry).closeAll(GatewayWebSocketDrain.GOING_AWAY);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("옮겨 가지 않은 세션은 drain-timeout 뒤 1001로 닫는다")
    void closesRemainingAfterTimeout() {
        when(registry.size()).thenReturn(1);

        long started = System.nanoTime();
        drain(Duration.ofMillis(300)).stop();

        verify(registry).closeAll(GatewayWebSocketDrain.GOING_AWAY);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(250), Duration.ofSeconds(2));
        assertThat(GatewayWebSocketDrain.GOING_AWAY.getCode()).isEqualTo(1001);
    }

    @Test
    @DisplayName("세션이 없으면 DRAIN도 대기도 없다")
    void skipsWithoutSessions() {
        when(registry.size()).thenReturn(0);

        drain(Duration.ofSeconds(5)).stop();

        verify(registry, never()).noticeAll(anyString());
        verify(registry).closeAll(any());
    }

    @Test
    @DisplayName("웹 서버 graceful shutdown보다 먼저 멈추는 phase다")
    void stopsBeforeWebServerGracefulShutdown() {
        assertThat(drain(Duration.ofSeconds(5)).getPhase()).isGreaterThan(SmartLifecycle.DEFAULT_PHASE - 1024);
    }
}
