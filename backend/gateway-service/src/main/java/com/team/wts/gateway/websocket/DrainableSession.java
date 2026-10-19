package com.team.wts.gateway.websocket;

import java.util.Map;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 브라우저 쪽 WebSocket 세션에 gateway 자신의 알림(DRAIN, CLAUDE.md §68)을 끼워 넣을 수 있게 감싼다.
 *
 * <p>Spring Cloud Gateway의 프록시는 backend(market-service)에서 받은 메시지를 {@link #send}로 한 번 흘려보낸다.
 * 그 흐름에 알림 흐름을 합친다. 프록시 흐름이 끝나면(backend가 닫힘) 알림 흐름도 끝내서 세션이 정상 종료되게 한다.
 * 나머지는 모두 감싼 세션에 그대로 넘긴다.
 */
final class DrainableSession implements WebSocketSession {

    private final WebSocketSession delegate;
    private final Sinks.Many<String> notices = Sinks.many().unicast().onBackpressureBuffer();

    DrainableSession(WebSocketSession delegate) {
        this.delegate = delegate;
    }

    /** 브라우저에 텍스트 한 건을 보낸다. 세션이 이미 끝났으면 아무 일도 없다. */
    void notice(String text) {
        notices.tryEmitNext(text);
    }

    @Override
    public Mono<Void> send(Publisher<WebSocketMessage> messages) {
        Flux<WebSocketMessage> proxied = Flux.from(messages).doFinally(signal -> notices.tryEmitComplete());
        return delegate.send(Flux.merge(proxied, notices.asFlux().map(delegate::textMessage)));
    }

    // ── 그대로 넘긴다 ───────────────────────────────────────

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public HandshakeInfo getHandshakeInfo() {
        return delegate.getHandshakeInfo();
    }

    @Override
    public DataBufferFactory bufferFactory() {
        return delegate.bufferFactory();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public Flux<WebSocketMessage> receive() {
        return delegate.receive();
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public Mono<Void> close(CloseStatus status) {
        return delegate.close(status);
    }

    @Override
    public Mono<CloseStatus> closeStatus() {
        return delegate.closeStatus();
    }

    @Override
    public WebSocketMessage textMessage(String payload) {
        return delegate.textMessage(payload);
    }

    @Override
    public WebSocketMessage binaryMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
        return delegate.binaryMessage(payloadFactory);
    }

    @Override
    public WebSocketMessage pingMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
        return delegate.pingMessage(payloadFactory);
    }

    @Override
    public WebSocketMessage pongMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
        return delegate.pongMessage(payloadFactory);
    }
}
