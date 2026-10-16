package com.team.wts.market.feed.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.sun.net.httpserver.HttpServer;
import com.team.wts.common.feed.FeedSignature;
import com.team.wts.market.config.MarketProperties;

/** 실제 HTTP로 보내 받는 쪽이 같은 바이트로 서명을 검증할 수 있는지 본다. */
class CorebankFeedClientTest {

    private static final String SECRET = "test-feed-secret";
    private static final Instant NOW = Instant.parse("2026-10-15T01:00:00Z");

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> signatureHeader = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            signatureHeader.set(exchange.getRequestHeaders().getFirst(FeedSignature.HEADER));
            body.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "{\"accepted\":2,\"skipped\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("본문 바이트에 서명해 보낸다. 받는 쪽이 같은 키로 검증할 수 있다")
    void sendsSignedBody() {
        String json = "{\"events\":[{\"eventType\":\"market.price.updated\"}]}";

        CorebankFeedClient.Receipt receipt = client().send(json);

        assertThat(receipt).isEqualTo(new CorebankFeedClient.Receipt(2, 1));

        assertThat(path.get()).isEqualTo("/internal/market/prices");
        assertThat(contentType.get()).startsWith("application/json");
        assertThat(new String(body.get(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(json);
        assertThat(new FeedSignature(SECRET)
                .verify(signatureHeader.get(), body.get(), NOW, Duration.ofSeconds(60))).isTrue();
    }

    @Test
    @DisplayName("2xx가 아니면 RestClientException으로 알린다")
    void reportsNon2xx() {
        status.set(503);

        assertThatThrownBy(() -> client().send("{\"events\":[]}")).isInstanceOf(RestClientException.class);
    }

    private CorebankFeedClient client() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/market/prices";
        MarketProperties properties = new MarketProperties("mock", List.of(), null, null, null,
                new MarketProperties.FeedRelay(true, url, SECRET, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        return new CorebankFeedClient(properties, RestClient.builder(), Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
