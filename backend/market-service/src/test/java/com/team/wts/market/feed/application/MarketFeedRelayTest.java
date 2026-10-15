package com.team.wts.market.feed.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.market.config.MarketProperties;
import com.team.wts.market.feed.adapter.out.http.CorebankFeedClient;

class MarketFeedRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-15T01:00:00Z");

    private final CorebankFeedClient client = mock(CorebankFeedClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MarketFeedRelay relay = new MarketFeedRelay(client, objectMapper,
            new MarketProperties("mock", List.of(), null, null,
                    new MarketProperties.Price(Duration.ofSeconds(5)), null),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("묶음 안에서 종목마다 가장 새로운 이벤트 하나만, 받은 문자열 그대로 보낸다")
    void sendsOnlyTheLatestPerSymbol() throws Exception {
        String samsungOld = event("005930", "80000", NOW.minusSeconds(2));
        String hynix = event("000660", "200000", NOW.minusSeconds(1));
        String samsungNew = event("005930", "80100", NOW.minusSeconds(1));

        int sent = relay.relay(List.of(samsungOld, hynix, samsungNew));

        assertThat(sent).isEqualTo(2);
        JsonNode events = objectMapper.readTree(sentBody()).path("events");
        List<String> sentEvents = new ArrayList<>();
        events.forEach(e -> sentEvents.add(e.toString()));
        assertThat(sentEvents).containsExactlyInAnyOrder(
                objectMapper.readTree(samsungNew).toString(), objectMapper.readTree(hynix).toString());
    }

    @Test
    @DisplayName("순서가 뒤바뀌어 와도 timestamp가 더 새로운 쪽을 남긴다")
    void keepsTheNewerTimestampEvenIfItCameFirst() throws Exception {
        String newer = event("005930", "80100", NOW.minusSeconds(1));
        String older = event("005930", "80000", NOW.minusSeconds(2));

        relay.relay(List.of(newer, older));

        assertThat(objectMapper.readTree(sentBody()).path("events").get(0).path("payload").path("price").asText())
                .isEqualTo("80100");
    }

    @Test
    @DisplayName("max-age를 넘긴 시세와 형식이 깨진 메시지는 보내지 않는다. 남는 게 없으면 요청하지 않는다")
    void dropsStaleAndMalformed() {
        int sent = relay.relay(List.of(event("005930", "80000", NOW.minusSeconds(6)), "not-json",
                "{\"payload\":{\"symbol\":\"005930\",\"timestamp\":\"yesterday\"}}"));

        assertThat(sent).isZero();
        verify(client, never()).send(anyString());
    }

    @Test
    @DisplayName("보내기에 실패하면 예외를 던지지 않고 그 묶음을 버린다")
    void swallowsSendFailure() {
        doThrow(new ResourceAccessException("connect timed out")).when(client).send(anyString());

        int sent = relay.relay(List.of(event("005930", "80000", NOW)));

        assertThat(sent).isZero();
    }

    private String sentBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).send(body.capture());
        return body.getValue();
    }

    /** market-service {@code KafkaMarketEventPublisher}가 내는 봉투와 같은 모양. */
    private static String event(String symbol, String price, Instant timestamp) {
        return """
                {"eventId":"%s","eventType":"market.price.updated","occurredAt":"%s","traceId":null,\
                "aggregateId":"%s","version":1,"payload":{"symbol":"%s","price":%s,"previousClose":80000,\
                "change":0,"changeRate":0,"volume":1,"timestamp":"%s"}}"""
                .formatted(UUID.randomUUID(), timestamp, symbol, symbol, price, timestamp);
    }
}
