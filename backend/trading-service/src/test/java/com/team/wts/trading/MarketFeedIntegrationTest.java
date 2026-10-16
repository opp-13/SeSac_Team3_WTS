package com.team.wts.trading;

import static com.team.wts.trading.support.MarketEventFixture.waitUntil;
import static com.team.wts.trading.support.TradingApiClient.order;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.team.wts.common.event.DomainEvent;
import com.team.wts.common.event.KafkaTopics;
import com.team.wts.common.feed.FeedSignature;
import com.team.wts.trading.execution.domain.ExecutionRepository;
import com.team.wts.trading.support.IntegrationTestBase;
import com.team.wts.trading.support.MarketEventFixture;
import com.team.wts.trading.support.MarketPriceFixture;
import com.team.wts.trading.support.TradingApiClient;

/**
 * channel에서 중계해 온 시세를 받아 이 tier의 Valkey · Kafka에 다시 내고, 그 시세로 주문이 체결되는지 본다.
 * (CLAUDE.md §65.7, ADR-0021)
 *
 * <p>요청 본문은 market-service가 내는 {@code market.price.updated} 봉투를 손으로 쓴다.
 * 두 서비스가 JSON 형식으로만 연결돼 있다는 사실을 테스트에서도 유지한다.
 */
@Import(MarketFeedIntegrationTest.Fixtures.class)
@TestPropertySource(properties = {
        "trading.market-feed.enabled=true",
        "trading.market-feed.secret=" + MarketFeedIntegrationTest.SECRET,
        // 이 클래스는 설정이 달라 컨텍스트가 따로 뜨고, 캐시돼 JVM 끝까지 산다. 같은 컨슈머 그룹이면
        // 파티션 1개를 다른 테스트 클래스의 소비자와 나눠 가져 한쪽이 영원히 못 받는다 (IntegrationTestBase 참고).
        "trading.pending-order.consumer-group=trading-service-pending-order-market-feed-it",
})
class MarketFeedIntegrationTest extends IntegrationTestBase {

    static final String SECRET = "test-feed-secret";
    private static final String HYNIX = "000660";
    private static final String PATH = "/internal/market/prices";

    @TestConfiguration
    static class Fixtures {

        /** 운영에서는 브로커가 자동으로 만든다. 소비자가 파티션을 배정받으려면 미리 있어야 한다. */
        @Bean
        NewTopic marketPriceUpdatedTopic() {
            return TopicBuilder.name(KafkaTopics.MARKET_PRICE_UPDATED).partitions(1).replicas(1).build();
        }

        @Bean
        MarketEventFixture marketEventFixture(KafkaTemplate<String, String> kafka,
                KafkaListenerEndpointRegistry registry) {
            return new MarketEventFixture(kafka, registry);
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TradingApiClient api;

    @Autowired
    private MarketPriceFixture marketPrice;

    @Autowired
    private MarketEventFixture marketEvents;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ExecutionRepository executions;

    private final FeedSignature signature = new FeedSignature(SECRET);

    @BeforeEach
    void setUp() {
        marketPrice.clear(HYNIX);
        marketEvents.startListeners();
    }

    @Test
    @DisplayName("서명한 시세를 받으면 Valkey에 market-service와 같은 형식으로 쓰고, 시장가 주문이 그 가격으로 체결된다")
    void relayedPriceFillsMarketOrder() {
        String sent = event(HYNIX, "201000", Instant.now());
        assertThat(sent).as("발행자 형식: 시각이 숫자").containsPattern("\"timestamp\":\\d+\\.\\d+");
        ResponseEntity<Map<String, Object>> response = post(body(sent));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("accepted", 1).containsEntry("skipped", 0);

        String cached = redis.opsForValue().get("market:price:" + HYNIX);
        assertThat(cached).contains("\"symbol\":\"000660\"", "\"price\":201000", "\"previousClose\":200000",
                "\"volume\":1234");
        // market-service의 Valkey 값과 같이 ISO 문자열로 쓴다 (이벤트에는 숫자로 왔다).
        assertThat(cached).containsPattern("\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T[^\"]+Z\"");
        assertThat(cached).doesNotContain("changeRate");

        String user = UUID.randomUUID().toString();
        Map<String, Object> placed = api.place(user, order(HYNIX, "BUY", "MARKET", 3, null)).getBody();
        assertThat(placed).containsEntry("status", "FILLED");
        long orderId = ((Number) placed.get("orderId")).longValue();
        assertThat(executions.findByOrderIdOrderByIdAsc(orderId).get(0).price()).isEqualByComparingTo("201000");
    }

    @Test
    @DisplayName("받은 시세는 Kafka market.price.updated로 다시 나가 미체결 지정가 주문을 체결한다")
    void relayedPriceFillsPendingLimitOrder() {
        post(body(event(HYNIX, "200000", Instant.now())));
        String user = UUID.randomUUID().toString();
        Map<String, Object> placed = api.place(user, order(HYNIX, "BUY", "LIMIT", 2, "199000")).getBody();
        long orderId = ((Number) placed.get("orderId")).longValue();
        assertThat(placed).containsEntry("status", "ACCEPTED");

        post(body(event(HYNIX, "198500", Instant.now().plusMillis(1))));

        waitUntil("중계 시세로 지정가 체결", () -> executions.findByOrderIdOrderByIdAsc(orderId).size() == 1);
        assertThat(executions.findByOrderIdOrderByIdAsc(orderId).get(0).price()).isEqualByComparingTo("198500");
    }

    @Test
    @DisplayName("시각이 ISO 문자열인 이벤트도 받는다")
    void acceptsIsoTimestamps() {
        ResponseEntity<Map<String, Object>> response = post(body(isoEvent(HYNIX, "202000", Instant.now())));

        assertThat(response.getBody()).containsEntry("accepted", 1);
        assertThat(redis.opsForValue().get("market:price:" + HYNIX)).contains("\"price\":202000");
    }

    @Test
    @DisplayName("서명이 틀리면 401이고 아무것도 쓰지 않는다")
    void rejectsBadSignature() {
        String body = body(event(HYNIX, "201000", Instant.now()));
        String forged = new FeedSignature("another-secret")
                .sign(body.getBytes(StandardCharsets.UTF_8), Instant.now());

        ResponseEntity<Map<String, Object>> noHeader = post(body, null);
        ResponseEntity<Map<String, Object>> wrongKey = post(body, forged);

        assertThat(noHeader.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongKey.getBody()).containsEntry("code", "UNAUTHORIZED");
        assertThat(redis.hasKey("market:price:" + HYNIX)).isFalse();
    }

    @Test
    @DisplayName("오래된 시세 · 이미 가진 값보다 새롭지 않은 시세 · 형식이 깨진 이벤트는 건너뛴다")
    void skipsStaleOlderAndMalformed() {
        Instant now = Instant.now();
        post(body(event(HYNIX, "201000", now)));

        String stale = event(HYNIX, "150000", now.minusSeconds(60));
        String older = event(HYNIX, "150000", now.minusMillis(500));
        String malformed = event("BAD", "150000", now.plusMillis(1));
        ResponseEntity<Map<String, Object>> response = post(body(stale, older, malformed));

        assertThat(response.getBody()).containsEntry("accepted", 0).containsEntry("skipped", 3);
        assertThat(redis.opsForValue().get("market:price:" + HYNIX)).contains("\"price\":201000");
    }

    @Test
    @DisplayName("본문이 JSON이 아니면 400")
    void rejectsNonJsonBody() {
        assertThat(post("not-json").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Map<String, Object>> post(String body) {
        return post(body, signature.sign(body.getBytes(StandardCharsets.UTF_8), Instant.now()));
    }

    private ResponseEntity<Map<String, Object>> post(String body, String signatureHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signatureHeader != null) {
            headers.set(FeedSignature.HEADER, signatureHeader);
        }
        return rest.exchange(PATH, org.springframework.http.HttpMethod.POST, new HttpEntity<>(body, headers),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
    }

    private static String body(String... events) {
        return "{\"events\":[" + String.join(",", events) + "]}";
    }

    /**
     * market-service {@code KafkaMarketEventPublisher}가 내는 봉투. 발행자와 같은 매퍼로 쓰므로 시각이 숫자(epoch 초.나노)다.
     * 운영과 같은 형식이다 (MarketEventFixture 참고).
     */
    private static String event(String symbol, String price, Instant timestamp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", symbol);
        payload.put("price", new BigDecimal(price));
        payload.put("previousClose", new BigDecimal("200000"));
        payload.put("change", BigDecimal.ZERO);
        payload.put("changeRate", BigDecimal.ZERO);
        payload.put("volume", 1234L);
        payload.put("timestamp", timestamp);
        DomainEvent<Map<String, Object>> event = new DomainEvent<>(UUID.randomUUID().toString(),
                KafkaTopics.MARKET_PRICE_UPDATED, timestamp, UUID.randomUUID().toString(), symbol,
                DomainEvent.CURRENT_VERSION, payload);
        try {
            return MarketEventFixture.PRODUCER_MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 시각을 ISO 문자열로 쓴 봉투. 다른 발행자가 Spring Boot 매퍼를 쓰는 경우다. */
    private static String isoEvent(String symbol, String price, Instant timestamp) {
        return """
                {"eventId":"%s","eventType":"market.price.updated","occurredAt":"%s","traceId":"%s",\
                "aggregateId":"%s","version":1,"payload":{"symbol":"%s","price":%s,"previousClose":200000,\
                "change":0,"changeRate":0,"volume":1234,"timestamp":"%s"}}"""
                .formatted(UUID.randomUUID(), timestamp, UUID.randomUUID(), symbol, symbol, price, timestamp);
    }
}
