package com.team.wts.market.feed.adapter.out.http;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.team.wts.common.feed.FeedSignature;
import com.team.wts.market.config.MarketProperties;

/**
 * corebank trading-service의 시세 수신 경로로 보낸다. (CLAUDE.md §65.4, ADR-0021)
 *
 * <p>본문 바이트에 서명해 {@link FeedSignature#HEADER}에 싣는다. 받는 쪽은 같은 바이트로 검증한다.
 * 재시도하지 않는다. 실패는 호출자에게 {@link RestClientException}으로 알린다.
 */
@Component
@ConditionalOnProperty(prefix = "market.feed-relay", name = "enabled", havingValue = "true")
public class CorebankFeedClient {

    private final RestClient rest;
    private final FeedSignature signature;
    private final Clock clock;
    private final String url;

    public CorebankFeedClient(MarketProperties properties, RestClient.Builder builder, Clock clock) {
        MarketProperties.FeedRelay relay = properties.feedRelay();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(relay.connectTimeout());
        requestFactory.setReadTimeout(relay.readTimeout());

        this.rest = builder.requestFactory(requestFactory).build();
        this.signature = new FeedSignature(relay.secret());
        this.clock = clock;
        this.url = relay.url();
    }

    /**
     * @return 받는 쪽이 반영한 건수와 건너뛴 건수 (§65.4 응답). 본문이 없으면 0 · 0
     * @throws RestClientException 연결 실패, 시간 초과, 2xx가 아닌 응답
     */
    public Receipt send(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Receipt receipt = rest.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header(FeedSignature.HEADER, signature.sign(bytes, clock.instant()))
                .body(bytes)
                .retrieve()
                .body(Receipt.class);
        return receipt == null ? new Receipt(0, 0) : receipt;
    }

    /** trading-service {@code MarketFeedService.Result}와 같은 JSON. 클래스는 공유하지 않는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Receipt(int accepted, int skipped) {
    }
}
