package com.team.wts.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.market.quote.adapter.ws.MarketSessionRegistry;
import com.team.wts.market.quote.adapter.ws.WebSocketShutdown;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.fanout.QuoteFanoutChannels;
import com.team.wts.market.support.IntegrationTestBase;
import com.team.wts.market.support.MarketDataFixture;

/**
 * market-ingest가 Valkey pub/sub로 내보낸 시세 · 호가가 이 앱의 WebSocket 클라이언트에 닿는다. (CLAUDE.md §66.3)
 *
 * <p>테스트가 market-ingest 대신 채널에 발행한다(같은 market-core 채널 이름 · 같은 JSON). 구독 직후의 스냅샷을 받은 뒤
 * 발행한 메시지가 fan-out 경로로 도착하는지 본다.
 */
class QuoteFanoutIntegrationTest extends IntegrationTestBase {

    private static final String SAMSUNG = "005930";

    @LocalServerPort
    private int port;

    @Autowired
    private MarketDataFixture fixture;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MarketSessionRegistry registry;

    @Autowired
    private WebSocketShutdown shutdown;

    private WebSocketSession session;

    @AfterEach
    void close() throws Exception {
        if (session != null && session.isOpen()) {
            session.close();
        }
    }

    @Test
    @DisplayName("Valkey pub/sub로 온 시세와 호가가 구독한 WebSocket 세션에 간다")
    void deliversTicksThroughValkeyFanout() throws Exception {
        fixture.seedStocks();
        fixture.seedQuote(SAMSUNG, "80000"); // 구독 스냅샷이 있도록 캐시를 채운다
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
                received.add(objectMapper.readTree(message.getPayload()));
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws/market")).get(10, TimeUnit.SECONDS);

        session.sendMessage(new TextMessage("{\"type\":\"SUBSCRIBE\",\"symbols\":[\"" + SAMSUNG + "\"]}"));
        assertThat(next(received, "SUBSCRIBED")).isNotNull();
        // 구독한 종목은 시청 수요로 남는다. market-ingest가 읽어 모의 시세를 만든다 (§67).
        Double expiresAt = redis.opsForZSet().score("market:demand:viewers", SAMSUNG);
        assertThat(expiresAt).as("시청 수요").isNotNull();
        assertThat(expiresAt.longValue()).isGreaterThan(System.currentTimeMillis());
        // 구독 스냅샷(PRICE · ORDERBOOK)을 비운다. 이후 오는 것은 fan-out 경로뿐이다.
        drain(received, Duration.ofSeconds(1));

        // market-ingest의 ValkeyQuoteFanoutPublisher와 같은 채널 · 같은 JSON
        MarketPrice tick = new MarketPrice(SAMSUNG, new java.math.BigDecimal("80700"),
                new java.math.BigDecimal("80000"), 200_000L, java.time.Instant.now());
        redis.convertAndSend(QuoteFanoutChannels.PRICE, objectMapper.writeValueAsString(tick));
        redis.convertAndSend(QuoteFanoutChannels.ORDER_BOOK, objectMapper.writeValueAsString(
                MarketDataFixture.orderBook(SAMSUNG, new java.math.BigDecimal("80700"))));

        JsonNode price = next(received, "PRICE");
        JsonNode orderbook = next(received, "ORDERBOOK");
        assertThat(price).as("fan-out 시세").isNotNull();
        assertThat(price.path("symbol").asText()).isEqualTo(SAMSUNG);
        assertThat(price.path("price").decimalValue()).isEqualByComparingTo("80700");
        assertThat(price.path("source").asText()).isEqualTo("REALTIME");
        assertThat(orderbook).as("fan-out 호가").isNotNull();
        assertThat(orderbook.path("asks").size()).isEqualTo(5);
    }

    @Test
    @DisplayName("인스턴스가 내려가면 먼저 DRAIN을 받고, 옮겨 가지 않으면 drain-timeout 뒤 1001로 닫힌다 (§68)")
    void drainsThenClosesWithGoingAwayOnShutdown() throws Exception {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
                received.add(objectMapper.readTree(message.getPayload()));
            }

            @Override
            public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
                closed.complete(status);
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws/market")).get(10, TimeUnit.SECONDS);
        awaitUntil(Duration.ofSeconds(5), () -> registry.sessionCount() > 0);

        // 컨텍스트를 닫지 않고 종료 단계만 실행한다. 같은 컨텍스트를 다른 테스트가 함께 쓴다.
        // stop()은 drain-timeout(테스트 500ms)까지 기다리므로 다른 스레드에서 부른다.
        CompletableFuture<Void> stopping = CompletableFuture.runAsync(shutdown::stop);

        assertThat(next(received, "DRAIN")).as("종료 예고").isNotNull();
        assertThat(closed.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.GOING_AWAY.getCode());
        stopping.get(10, TimeUnit.SECONDS);
        shutdown.start();
    }

    @Test
    @DisplayName("DRAIN을 받은 클라이언트가 스스로 떠나면 종료가 drain-timeout을 기다리지 않는다 (§68)")
    void shutdownEndsEarlyWhenClientsMoveAway() throws Exception {
        session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
                if ("DRAIN".equals(objectMapper.readTree(message.getPayload()).path("type").asText())) {
                    s.close(CloseStatus.NORMAL); // 새 연결로 옮겨 간 클라이언트처럼
                }
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws/market")).get(10, TimeUnit.SECONDS);
        awaitUntil(Duration.ofSeconds(5), () -> registry.sessionCount() > 0);

        long started = System.nanoTime();
        shutdown.stop();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        shutdown.start();

        assertThat(registry.sessionCount()).isZero();
        assertThat(elapsedMillis).as("drain-timeout(500ms)보다 빨리").isLessThan(450L);
    }

    @Test
    @DisplayName("PING에 PONG으로 답한다 - 시세가 흐르지 않는 연결도 유휴 제한에 걸리지 않게 (§68)")
    void answersPingWithPong() throws Exception {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
                received.add(objectMapper.readTree(message.getPayload()));
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws/market")).get(10, TimeUnit.SECONDS);

        session.sendMessage(new TextMessage("{\"type\":\"PING\"}"));

        assertThat(next(received, "PONG")).isNotNull();
    }

    private static JsonNode next(BlockingQueue<JsonNode> queue, String type) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            JsonNode message = queue.poll(200, TimeUnit.MILLISECONDS);
            if (message != null && type.equals(message.path("type").asText())) {
                return message;
            }
        }
        return null;
    }

    private static void drain(BlockingQueue<JsonNode> queue, Duration quiet) throws InterruptedException {
        while (queue.poll(quiet.toMillis(), TimeUnit.MILLISECONDS) != null) {
            // 조용해질 때까지 버린다
        }
    }
}
