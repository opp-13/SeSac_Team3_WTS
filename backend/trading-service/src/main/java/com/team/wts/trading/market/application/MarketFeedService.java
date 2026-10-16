package com.team.wts.trading.market.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.wts.common.event.KafkaTopics;
import com.team.wts.common.market.StockSymbol;
import com.team.wts.trading.config.MarketPriceProperties;
import com.team.wts.trading.market.adapter.out.cache.ValkeyMarketPriceMirror;
import com.team.wts.trading.market.adapter.out.kafka.KafkaMarketPriceRepublisher;
import com.team.wts.trading.market.domain.MarketPrice;
import com.team.wts.trading.market.domain.MarketPriceQuery;

/**
 * channel에서 중계해 온 시세를 이 tier에 다시 낸다. (CLAUDE.md §65.3, ADR-0021)
 *
 * <p>이벤트마다 독립적으로 판단한다. 하나가 깨져도 나머지는 반영한다.
 * <ul>
 *   <li>형식이 틀렸거나, max-age(§11)를 넘겼거나, 이미 가진 값보다 새롭지 않으면 버린다</li>
 *   <li>그 외에는 Valkey에 최신값을 쓰고, Kafka에 받은 봉투를 그대로 낸다</li>
 * </ul>
 *
 * <p>"더 새로운 값만" 규칙 덕분에 같은 요청이 다시 와도(재전송 · 중복) 해가 없다.
 * 비교와 쓰기가 원자적이지 않지만, 보내는 쪽은 market-service 한 대(replica 1)가 순서대로 보낸다.
 *
 * <p>시각은 Jackson으로 읽는다. 이벤트의 시각은 숫자(epoch 초.나노)로 온다 - 발행자가 Spring Kafka 기본 매퍼를 쓴다.
 * ISO 문자열도 받는다. Valkey에는 market-service와 같은 ISO 문자열로 쓴다.
 *
 * <p>건너뛴 이유는 1분마다 한 줄로 요약한다. 요청마다 로그를 남기지 않는다.
 */
@Service
@ConditionalOnProperty(prefix = "trading.market-feed", name = "enabled", havingValue = "true")
public class MarketFeedService {

    private static final Logger log = LoggerFactory.getLogger(MarketFeedService.class);

    private static final Duration SUMMARY_INTERVAL = Duration.ofMinutes(1);

    private final MarketPriceQuery prices;
    private final ValkeyMarketPriceMirror cache;
    private final KafkaMarketPriceRepublisher events;
    private final MarketPriceProperties priceProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // HTTP 요청 스레드 여럿이 함께 쓴다. 요청이 초당 몇 건이라 잠금으로 충분하다.
    private final Object statsLock = new Object();
    private Stats stats = new Stats();
    private Instant lastSummaryAt;

    public MarketFeedService(MarketPriceQuery prices, ValkeyMarketPriceMirror cache,
            KafkaMarketPriceRepublisher events, MarketPriceProperties priceProperties,
            ObjectMapper objectMapper, Clock clock) {
        this.prices = prices;
        this.cache = cache;
        this.events = events;
        this.priceProperties = priceProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.lastSummaryAt = clock.instant();
    }

    public Result accept(List<JsonNode> received) {
        Stats batch = new Stats();
        for (JsonNode event : received) {
            batch.count(acceptOne(event));
        }
        record(batch);
        return new Result(batch.accepted, received.size() - batch.accepted);
    }

    private Outcome acceptOne(JsonNode event) {
        Optional<Quote> parsed = parse(event);
        if (parsed.isEmpty()) {
            return Outcome.MALFORMED;
        }
        Quote quote = parsed.get();

        // 밀려서 온 시세로 체결하지 않는다 (§11). 받는 쪽에서도 다시 본다.
        if (quote.timestamp().plus(priceProperties.maxAge()).isBefore(clock.instant())) {
            return Outcome.STALE;
        }
        Optional<MarketPrice> current = prices.findLatest(quote.symbol());
        if (current.isPresent() && !quote.timestamp().isAfter(current.get().timestamp())) {
            return Outcome.NOT_NEWER;
        }

        if (!cache.save(quote.symbol(), toCacheJson(event.path("payload"), quote.timestamp()))) {
            return Outcome.WRITE_FAILED;
        }
        events.publish(quote.symbol(), event.path("eventId").asText(null), toJson(event));
        return Outcome.ACCEPTED;
    }

    private Optional<Quote> parse(JsonNode event) {
        if (!KafkaTopics.MARKET_PRICE_UPDATED.equals(event.path("eventType").asText(null))) {
            return Optional.empty();
        }
        JsonNode payload = event.path("payload");
        String symbol = payload.path("symbol").asText(null);
        JsonNode price = payload.path("price");
        JsonNode timestamp = payload.path("timestamp");
        if (!StockSymbol.isValid(symbol) || !price.isNumber()
                || price.decimalValue().compareTo(BigDecimal.ZERO) <= 0
                || timestamp.isMissingNode() || timestamp.isNull()) {
            return Optional.empty();
        }
        try {
            // 숫자(epoch 초.나노)와 ISO 문자열을 모두 읽는다. MarketPriceUpdatedConsumer와 같은 규칙이다.
            Instant at = objectMapper.treeToValue(timestamp, Instant.class);
            return at == null ? Optional.empty() : Optional.of(new Quote(symbol, at));
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * market-service {@code ValkeyQuoteCache}가 쓰는 값과 같은 모양으로 만든다
     * ({@code symbol, price, previousClose, volume, timestamp}). 이벤트 payload에만 있는
     * change · changeRate는 캐시 값에 없다. timestamp는 이 서비스의 ObjectMapper가 ISO 문자열로 쓴다
     * (market-service의 Valkey 값과 같다). 이벤트에는 숫자로 온다.
     */
    private String toCacheJson(JsonNode payload, Instant timestamp) {
        ObjectNode value = objectMapper.createObjectNode();
        for (String field : List.of("symbol", "price", "previousClose", "volume")) {
            if (payload.has(field)) {
                value.set(field, payload.get(field));
            }
        }
        value.set("timestamp", objectMapper.valueToTree(timestamp));
        return toJson(value);
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // 방금 파싱한 트리라 실패할 수 없다. 오면 버그다.
            throw new IllegalStateException("JSON 직렬화 실패", e);
        }
    }

    private void record(Stats batch) {
        Stats summary = null;
        Instant now = clock.instant();
        synchronized (statsLock) {
            stats.add(batch);
            if (!lastSummaryAt.plus(SUMMARY_INTERVAL).isAfter(now)) {
                summary = stats;
                stats = new Stats();
                lastSummaryAt = now;
            }
        }
        if (summary != null && summary.total() > 0) {
            log.info("중계 시세 수신: 반영 {}건, 건너뜀 오래됨 {}건 · 더 새롭지 않음 {}건 · 형식 {}건 · 쓰기 실패 {}건",
                    summary.accepted, summary.stale, summary.notNewer, summary.malformed, summary.writeFailed);
        }
    }

    private enum Outcome { ACCEPTED, STALE, NOT_NEWER, MALFORMED, WRITE_FAILED }

    private static final class Stats {
        int accepted;
        int stale;
        int notNewer;
        int malformed;
        int writeFailed;

        void count(Outcome outcome) {
            switch (outcome) {
                case ACCEPTED -> accepted++;
                case STALE -> stale++;
                case NOT_NEWER -> notNewer++;
                case MALFORMED -> malformed++;
                case WRITE_FAILED -> writeFailed++;
            }
        }

        void add(Stats other) {
            accepted += other.accepted;
            stale += other.stale;
            notNewer += other.notNewer;
            malformed += other.malformed;
            writeFailed += other.writeFailed;
        }

        int total() {
            return accepted + stale + notNewer + malformed + writeFailed;
        }
    }

    private record Quote(String symbol, Instant timestamp) {
    }

    /** @param skipped 형식 오류 · 오래됨 · 더 새롭지 않음 · 쓰기 실패 */
    public record Result(int accepted, int skipped) {
    }
}
