package com.team.wts.trading.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;

import org.springframework.kafka.support.JacksonUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.common.event.DomainEvent;
import com.team.wts.common.event.KafkaTopics;

/**
 * market-service 역할을 대신해 {@code market.price.updated}를 실제 브로커로 발행한다.
 *
 * <p>봉투 JSON을 여기서 직접 만든다. 두 서비스가 토픽의 JSON 형식으로만 연결돼 있다는 사실을
 * 테스트에서도 유지하기 위해서다. 형식이 어긋나면 이 테스트가 먼저 깨져야 한다.
 *
 * <p>직렬화는 market-service 발행자와 같은 매퍼로 한다. 발행자는 {@code value-serializer: JsonSerializer}라
 * Spring Kafka 기본 매퍼({@link JacksonUtils#enhancedObjectMapper()})를 쓰고, 시각을 숫자(epoch 초.나노)로 쓴다.
 * Spring Boot 매퍼로 만들면 ISO 문자열이 되어 운영과 다른 형식을 시험하게 된다 (2026-10-16, ADR-0021).
 */
public class MarketEventFixture {

    private final KafkaTemplate<String, String> kafka;
    private final KafkaListenerEndpointRegistry registry;

    /** market-service 발행자와 같은 매퍼. */
    public static final ObjectMapper PRODUCER_MAPPER = JacksonUtils.enhancedObjectMapper();

    public MarketEventFixture(KafkaTemplate<String, String> kafka, KafkaListenerEndpointRegistry registry) {
        this.kafka = kafka;
        this.registry = registry;
    }

    /**
     * 이 컨텍스트의 소비자를 켜고 파티션을 배정받을 때까지 기다린다.
     *
     * <p>배정 전에 발행하면 메시지를 놓친다. 이미 켜져 있으면 아무 일도 하지 않는다.
     */
    public void startListeners() {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (!container.isRunning()) {
                container.start();
            }
            ContainerTestUtils.waitForAssignment(container, 1);
        }
    }

    public void publishPrice(String symbol, String price) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", symbol);
        payload.put("price", new BigDecimal(price));
        payload.put("previousClose", new BigDecimal("80000"));
        payload.put("change", BigDecimal.ZERO);
        payload.put("changeRate", BigDecimal.ZERO);
        payload.put("volume", 1234L);
        payload.put("timestamp", Instant.now());

        DomainEvent<Map<String, Object>> event = DomainEvent.of(
                KafkaTopics.MARKET_PRICE_UPDATED, symbol, UUID.randomUUID().toString(), payload);
        try {
            kafka.send(KafkaTopics.MARKET_PRICE_UPDATED, symbol,
                    PRODUCER_MAPPER.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("시세 이벤트 발행 실패", e);
        }
    }

    /** 비동기 처리를 기다린다. */
    public static void waitUntil(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("기다리다 시간이 지났다: " + what);
    }
}
