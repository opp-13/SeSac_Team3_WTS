package com.team.wts.gateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 실제 Spring Cloud Gateway WebSocket 프록시를 거쳐 DRAIN이 브라우저 쪽에 닿는다. (CLAUDE.md §68)
 *
 * <p>backend(market-service 자리)는 테스트가 띄운 작은 WebSocket 서버다. 100ms마다 PRICE를 보낸다.
 * 종료 단계({@link GatewayWebSocketDrain#stop()})만 실행하고 컨텍스트는 닫지 않는다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "wts.auth.secret=gateway-test-secret-not-used-anywhere-else",
                "wts.gateway.websocket.drain-timeout=1500ms"})
class GatewayWebSocketDrainProxyTest {

    private static final DisposableServer BACKEND = HttpServer.create()
            .port(0)
            .route(routes -> routes.ws("/ws/market", (in, out) -> out.sendString(
                    Flux.interval(Duration.ofMillis(100)).map(i -> "{\"type\":\"PRICE\",\"seq\":" + i + "}"))))
            .bindNow();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("MARKET_SERVICE_WS_URI", () -> "ws://localhost:" + BACKEND.port());
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.disposeNow();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private GatewayWebSocketDrain drain;

    @Autowired
    private WebSocketDrainRegistry registry;

    @Test
    @DisplayName("gateway가 내려가면 프록시 중인 연결에 DRAIN이 끼어 나가고, 클라이언트가 떠나면 바로 끝난다")
    void injectsDrainAndEndsEarlyWhenClientLeaves() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        Disposable client = connect(received, true, new CompletableFuture<>());
        try {
            awaitPrice(received);

            long started = System.nanoTime();
            drain.stop();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            drain.start();

            assertThat(received).contains(GatewayWebSocketDrain.DRAIN);
            assertThat(elapsed).as("drain-timeout(1500ms)을 기다리지 않는다").isLessThan(Duration.ofMillis(1200));
            assertThat(registry.size()).isZero();
        } finally {
            client.dispose();
        }
    }

    @Test
    @DisplayName("DRAIN을 받고도 떠나지 않은 연결은 drain-timeout 뒤 1001로 닫힌다")
    void closesWithGoingAwayWhenClientStays() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        Disposable client = connect(received, false, closed);
        try {
            awaitPrice(received);

            drain.stop();
            drain.start();

            assertThat(received).contains(GatewayWebSocketDrain.DRAIN);
            assertThat(closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(1001);
        } finally {
            client.dispose();
        }
    }

    /** gateway의 /ws/market 에 붙는다. leaveOnDrain 이면 DRAIN을 받는 즉시 스스로 닫는다(새 연결로 옮겨 간 것처럼). */
    private Disposable connect(BlockingQueue<String> received, boolean leaveOnDrain,
            CompletableFuture<CloseStatus> closed) {
        return new ReactorNettyWebSocketClient()
                .execute(URI.create("ws://localhost:" + port + "/ws/market"), session -> {
                    session.closeStatus().subscribe(closed::complete);
                    Flux<String> inbound = session.receive().map(WebSocketMessage::getPayloadAsText)
                            .doOnNext(received::add);
                    if (leaveOnDrain) {
                        return inbound.filter(GatewayWebSocketDrain.DRAIN::equals).next()
                                .then(session.close(CloseStatus.NORMAL));
                    }
                    return inbound.then();
                })
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    private void awaitPrice(BlockingQueue<String> received) throws InterruptedException {
        String first = received.poll(10, TimeUnit.SECONDS);
        assertThat(first).as("프록시를 거친 backend 메시지").contains("PRICE");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (registry.size() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(registry.size()).isEqualTo(1);
    }
}
