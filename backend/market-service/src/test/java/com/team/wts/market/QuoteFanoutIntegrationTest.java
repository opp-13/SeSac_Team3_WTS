package com.team.wts.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        assertThat(orderbook).as("fan-out 호가").isNotNull();
        assertThat(orderbook.path("asks").size()).isEqualTo(5);
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
