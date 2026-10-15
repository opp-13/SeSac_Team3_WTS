package com.team.wts.market.feed.adapter.out.http;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

    /** @throws RestClientException 연결 실패, 시간 초과, 2xx가 아닌 응답 */
    public void send(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        rest.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header(FeedSignature.HEADER, signature.sign(bytes, clock.instant()))
                .body(bytes)
                .retrieve()
                .toBodilessEntity();
    }
}
