package com.team.wts.trading.market.adapter.in.web;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.team.wts.common.error.DomainException;
import com.team.wts.common.error.ErrorCode;
import com.team.wts.common.feed.FeedSignature;
import com.team.wts.trading.config.MarketFeedProperties;
import com.team.wts.trading.market.application.MarketFeedService;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * channel market-service가 중계하는 시세를 받는다. (CLAUDE.md §65.4, ADR-0021)
 *
 * <p>사용자 API가 아니다. gateway는 {@code /internal/**}를 라우팅하지 않고, corebank ALB가
 * 이 경로 하나만 channel에서 받는다. 공개 명세(§24)에 넣지 않는다.
 *
 * <p>본문을 바이트로 받는 이유: 서명은 보낸 바이트 그대로에 대해 검증해야 한다.
 * 객체로 받았다가 다시 직렬화하면 공백 · 숫자 표기가 달라져 서명이 맞지 않는다.
 */
@Hidden
@RestController
@ConditionalOnProperty(prefix = "trading.market-feed", name = "enabled", havingValue = "true")
public class MarketFeedController {

    /** 한 요청에 담을 수 있는 이벤트 수. 보내는 쪽은 종목별 최신값만 보내므로 넉넉하다. */
    static final int MAX_EVENTS = 500;

    private final MarketFeedService service;
    private final FeedSignature signature;
    private final MarketFeedProperties properties;
    private final ObjectReader reader;
    private final Clock clock;

    public MarketFeedController(MarketFeedService service, MarketFeedProperties properties,
            ObjectMapper objectMapper, Clock clock) {
        this.service = service;
        this.signature = new FeedSignature(properties.secret());
        this.properties = properties;
        // 소수를 double로 읽지 않는다. 시각(epoch 초.나노) 같은 숫자를 받은 그대로 Kafka에 다시 내기 위해서다.
        // double로 읽으면 나노초가 깎이고, 다시 쓸 때 지수 표기(1.79E9)로 바뀔 수 있다.
        this.reader = objectMapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(JsonNodeFactory.withExactBigDecimals(true));
        this.clock = clock;
    }

    @PostMapping(path = "/internal/market/prices", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MarketFeedService.Result receive(
            @RequestHeader(name = FeedSignature.HEADER, required = false) String signatureHeader,
            @RequestBody byte[] body) {
        if (!signature.verify(signatureHeader, body, clock.instant(), properties.maxClockSkew())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "시세 중계 서명이 올바르지 않습니다.");
        }
        return service.accept(readEvents(body));
    }

    private List<JsonNode> readEvents(byte[] body) {
        JsonNode events;
        try {
            events = reader.readTree(body).path("events");
        } catch (IOException e) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "본문이 JSON이 아닙니다.");
        }
        if (!events.isArray()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "events 배열이 없습니다.");
        }
        if (events.size() > MAX_EVENTS) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "events 는 " + MAX_EVENTS + "개까지입니다: " + events.size());
        }
        List<JsonNode> list = new ArrayList<>(events.size());
        events.forEach(list::add);
        return list;
    }
}
