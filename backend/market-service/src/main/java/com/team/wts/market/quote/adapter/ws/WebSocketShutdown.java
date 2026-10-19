package com.team.wts.market.quote.adapter.ws;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import com.team.wts.market.config.MarketWebSocketProperties;
import com.team.wts.market.quote.adapter.ws.dto.ServerMessages;

/**
 * 인스턴스가 내려갈 때 브라우저 WebSocket을 끊김 없이 다른 인스턴스로 넘긴다. (CLAUDE.md §66.6, §68)
 *
 * <pre>
 * 1. 모든 세션에 DRAIN 을 보낸다
 *    클라이언트는 이 연결을 쥔 채 새 연결을 맺고(preStop 동안 엔드포인트에서 빠졌으니 다른 인스턴스로 간다),
 *    새 연결이 구독 확인을 받으면 이 연결을 닫는다 → 시세 공백 0
 * 2. 세션이 모두 떠나거나 drain-timeout 이 지날 때까지 기다린다
 * 3. 남은 세션은 1001(Going Away)로 닫는다. 그런 클라이언트는 끊긴 뒤 재연결한다
 * </pre>
 *
 * <p>기본 phase는 웹 서버 graceful shutdown보다 먼저 멈춘다. 그래서 graceful shutdown은 기다릴 WebSocket 없이
 * 남은 REST 요청만 기다린다.
 */
@Component
public class WebSocketShutdown implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WebSocketShutdown.class);

    static final CloseStatus GOING_AWAY = CloseStatus.GOING_AWAY.withReason("server shutting down");
    private static final long POLL_MILLIS = 100L;

    private final MarketSessionRegistry registry;
    private final Duration drainTimeout;

    private volatile boolean running;

    public WebSocketShutdown(MarketSessionRegistry registry, MarketWebSocketProperties properties) {
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
        int before = registry.sessionCount();
        if (before > 0) {
            registry.sendToAll(ServerMessages.Drain.INSTANCE);
            awaitSessionsGone();
        }
        int remaining = registry.sessionCount();
        registry.closeAll(GOING_AWAY);
        log.info("종료: WebSocket 세션 {}개에 DRAIN, 스스로 옮겨 간 세션 {}개, 1001로 닫은 세션 {}개",
                before, before - remaining, remaining);
    }

    private void awaitSessionsGone() {
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        while (registry.sessionCount() > 0 && System.nanoTime() < deadline) {
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
