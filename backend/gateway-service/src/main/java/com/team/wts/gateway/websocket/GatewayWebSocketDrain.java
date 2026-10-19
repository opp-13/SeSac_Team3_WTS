package com.team.wts.gateway.websocket;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;

/**
 * gateway 인스턴스가 내려갈 때 브라우저 WebSocket을 끊김 없이 다른 인스턴스로 넘긴다. (CLAUDE.md §68)
 *
 * <pre>
 * 1. 이 인스턴스를 지나는 모든 브라우저 세션에 DRAIN 을 보낸다
 *    클라이언트는 이 연결을 쥔 채 새 연결을 맺는다(preStop 동안 ALB · Service에서 빠졌으니 다른 인스턴스로 간다)
 * 2. 세션이 모두 떠나거나 drain-timeout 이 지날 때까지 기다린다
 * 3. 남은 세션은 1001(Going Away)로 닫는다
 * </pre>
 *
 * <p>기본 phase는 웹 서버 graceful shutdown보다 먼저 멈춘다. 그래서 graceful shutdown은 기다릴 WebSocket 없이
 * 남은 HTTP 요청만 기다린다. market-service가 내려갈 때의 DRAIN은 backend가 보내고 이 gateway는 그대로 전달한다.
 */
@Component
public class GatewayWebSocketDrain implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebSocketDrain.class);

    /** market-service의 ServerMessages.Drain 과 같은 메시지. 클라이언트에게는 누가 보냈든 같은 뜻이다. */
    static final String DRAIN = "{\"type\":\"DRAIN\"}";
    static final CloseStatus GOING_AWAY = CloseStatus.GOING_AWAY.withReason("server shutting down");
    private static final long POLL_MILLIS = 100L;

    private final WebSocketDrainRegistry registry;
    private final Duration drainTimeout;

    private volatile boolean running;

    public GatewayWebSocketDrain(WebSocketDrainRegistry registry, GatewayWebSocketProperties properties) {
        this.registry = registry;
        this.drainTimeout = properties.drainTimeout();
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        int before = registry.size();
        if (before > 0) {
            registry.noticeAll(DRAIN);
            awaitSessionsGone();
        }
        int remaining = registry.size();
        registry.closeAll(GOING_AWAY);
        log.info("종료: WebSocket 세션 {}개에 DRAIN, 스스로 옮겨 간 세션 {}개, 1001로 닫은 세션 {}개",
                before, before - remaining, remaining);
    }

    private void awaitSessionsGone() {
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        while (registry.size() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
