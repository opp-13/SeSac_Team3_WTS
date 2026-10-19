package com.team.wts.gateway.websocket;

import java.util.List;

import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Spring Cloud Gateway가 WebSocket 핸드셰이크에 쓰는 서비스를 감싼다. (CLAUDE.md §68)
 *
 * <p>gateway의 WebSocket 라우트(/ws/market/**)는 이 서비스로 브라우저 연결을 받은 뒤 backend로 프록시한다.
 * 여기서 브라우저 쪽 세션을 {@link DrainableSession}으로 감싸 {@link WebSocketDrainRegistry}에 올려 둔다.
 * 그래야 gateway가 내려갈 때 프록시 흐름 사이에 DRAIN을 끼워 넣을 수 있다.
 */
public class DrainingWebSocketService implements WebSocketService {

    private final WebSocketService delegate;
    private final WebSocketDrainRegistry registry;

    public DrainingWebSocketService(WebSocketService delegate, WebSocketDrainRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public Mono<Void> handleRequest(ServerWebExchange exchange, WebSocketHandler handler) {
        return delegate.handleRequest(exchange, new WebSocketHandler() {
            @Override
            public List<String> getSubProtocols() {
                return handler.getSubProtocols();
            }

            @Override
            public Mono<Void> handle(WebSocketSession session) {
                DrainableSession drainable = new DrainableSession(session);
                registry.add(drainable);
                return handler.handle(drainable).doFinally(signal -> registry.remove(drainable));
            }
        });
    }
}
