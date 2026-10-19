package com.team.wts.gateway.websocket;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;

import reactor.core.publisher.Mono;

/**
 * 이 gateway 인스턴스를 지나는 브라우저 WebSocket 세션들. (CLAUDE.md §68)
 *
 * <p>종료할 때 모두에게 DRAIN을 보내고, 옮겨 가지 않은 세션을 닫기 위해 들고 있다. 세션이 끝나면 빠진다.
 */
@Component
public class WebSocketDrainRegistry {

    private static final Logger log = LoggerFactory.getLogger(WebSocketDrainRegistry.class);
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(2);

    private final Set<DrainableSession> sessions = ConcurrentHashMap.newKeySet();

    void add(DrainableSession session) {
        sessions.add(session);
    }

    void remove(DrainableSession session) {
        sessions.remove(session);
    }

    public int size() {
        return sessions.size();
    }

    /** 모든 세션에 텍스트를 보낸다. */
    public void noticeAll(String text) {
        sessions.forEach(session -> session.notice(text));
    }

    /** 남은 세션을 모두 닫는다. 한 세션의 실패가 나머지를 막지 않는다. */
    public void closeAll(CloseStatus status) {
        List<Mono<Void>> closes = sessions.stream()
                .map(session -> session.close(status)
                        .onErrorResume(e -> {
                            log.debug("WebSocket 종료 실패: sessionId={} ({})", session.getId(), e.getMessage());
                            return Mono.empty();
                        }))
                .toList();
        try {
            Mono.when(closes).block(CLOSE_WAIT);
        } catch (IllegalStateException e) {
            // 제한 시간 안에 닫히지 않은 세션은 이어지는 서버 종료가 끊는다.
            log.warn("WebSocket 세션 종료가 {} 안에 끝나지 않았다 ({})", CLOSE_WAIT, e.getMessage());
        }
    }
}
