package com.team.wts.gateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** 브라우저 쪽 세션에 DRAIN 끼워 넣기. (CLAUDE.md §68) */
class DrainableSessionTest {

    private final WebSocketSession delegate = mock(WebSocketSession.class);
    private final AtomicReference<Publisher<WebSocketMessage>> sent = new AtomicReference<>();

    DrainableSessionTest() {
        when(delegate.textMessage(anyString())).thenAnswer(invocation -> text(invocation.getArgument(0)));
        when(delegate.send(any())).thenAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return Mono.empty();
        });
    }

    @Test
    @DisplayName("프록시가 보내는 메시지 사이에 알림이 끼어 나가고, 프록시 흐름이 끝나면 함께 끝난다")
    void mergesNoticesIntoTheProxiedStream() {
        DrainableSession session = new DrainableSession(delegate);
        Sinks.Many<WebSocketMessage> proxied = Sinks.many().unicast().onBackpressureBuffer();
        session.send(proxied.asFlux()).block();

        Mono<List<String>> outbound = Flux.from(sent.get()).map(WebSocketMessage::getPayloadAsText).collectList()
                .cache();
        outbound.subscribe();
        proxied.tryEmitNext(text("{\"type\":\"PRICE\"}"));
        session.notice(GatewayWebSocketDrain.DRAIN);
        proxied.tryEmitComplete();

        assertThat(outbound.block(Duration.ofSeconds(2)))
                .containsExactly("{\"type\":\"PRICE\"}", GatewayWebSocketDrain.DRAIN);
    }

    private static WebSocketMessage text(String payload) {
        return new WebSocketMessage(WebSocketMessage.Type.TEXT,
                DefaultDataBufferFactory.sharedInstance.wrap(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
