package com.team.wts.trading.market.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
 */
@Service
@ConditionalOnProperty(prefix = "trading.market-feed", name = "enabled", havingValue = "true")
public class MarketFeedService {

    private static final Logger log = LoggerFactory.getLogger(MarketFeedService.class);

    private final MarketPriceQuery prices;
    private final ValkeyMarketPriceMirror cache;
    private final KafkaMarketPriceRepublisher events;
    private final MarketPriceProperties priceProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MarketFeedService(MarketPriceQuery prices, ValkeyMarketPriceMirror cache,
            KafkaMarketPriceRepublisher events, MarketPriceProperties priceProperties,
            ObjectMapper objectMapper, Clock clock) {
        this.prices = prices;
        this.cache = cache;
        this.events = events;
        this.priceProperties = priceProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Result accept(List<JsonNode> received) {
        int accepted = 0;
        for (JsonNode event : received) {
            if (acceptOne(event)) {
                accepted++;
            }
        }
        return new Result(accepted, received.size() - accepted);
    }

    private boolean acceptOne(JsonNode event) {
        Optional<Quote> parsed = parse(event);
        if (parsed.isEmpty()) {
            log.warn("중계 시세 형식 오류, 건너뜀: eventId={}", event.path("eventId").asText(null));
            return false;
        }
        Quote quote = parsed.get();

        // 밀려서 온 시세로 체결하지 않는다 (§11). 받는 쪽에서도 다시 본다.
        if (quote.timestamp().plus(priceProperties.maxAge()).isBefore(clock.instant())) {
            return false;
        }
        Optional<MarketPrice> current = prices.findLatest(quote.symbol());
        if (current.isPresent() && !quote.timestamp().isAfter(current.get().timestamp())) {
            return false;
        }

        if (!cache.save(quote.symbol(), toCacheJson(event.path("payload")))) {
            return false;
        }
        events.publish(quote.symbol(), event.path("eventId").asText(null), toJson(event));
        return true;
    }

    private static Optional<Quote> parse(JsonNode event) {
        if (!KafkaTopics.MARKET_PRICE_UPDATED.equals(event.path("eventType").asText(null))) {
            return Optional.empty();
        }
        JsonNode payload = event.path("payload");
        String symbol = payload.path("symbol").asText(null);
        JsonNode price = payload.path("price");
        if (!StockSymbol.isValid(symbol) || !price.isNumber()
                || price.decimalValue().compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Quote(symbol, Instant.parse(payload.path("timestamp").asText(""))));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * market-service {@code ValkeyQuoteCache}가 쓰는 값과 같은 필드만 남긴다
     * ({@code symbol, price, previousClose, volume, timestamp}). 이벤트 payload에만 있는
     * change · changeRate는 캐시 값에 없다.
     */
    private String toCacheJson(JsonNode payload) {
        ObjectNode value = objectMapper.createObjectNode();
        for (String field : List.of("symbol", "price", "previousClose", "volume", "timestamp")) {
            if (payload.has(field)) {
                value.set(field, payload.get(field));
            }
        }
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

    private record Quote(String symbol, Instant timestamp) {
    }

    /** @param skipped 형식 오류 · 오래됨 · 더 새롭지 않음 · 쓰기 실패 */
    public record Result(int accepted, int skipped) {
    }
}
