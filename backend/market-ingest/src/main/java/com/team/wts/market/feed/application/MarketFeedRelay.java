package com.team.wts.market.feed.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.market.config.MarketProperties;
import com.team.wts.market.feed.adapter.out.http.CorebankFeedClient;
import com.team.wts.market.feed.adapter.out.http.CorebankFeedClient.Receipt;

/**
 * 시세 이벤트 묶음을 corebank로 중계한다. (CLAUDE.md §65.3, ADR-0021)
 *
 * <p>시세는 다시 만들 수 있다. 유실보다 최신성이 중요하다 (§65.2-5).
 * <ul>
 *   <li>묶음 안에서 종목마다 가장 새로운 이벤트 하나만 보낸다</li>
 *   <li>max-age(§11)를 넘긴 이벤트는 보내지 않는다. 받아도 체결에 못 쓴다</li>
 *   <li>보내기에 실패하면 그 묶음을 버린다. 재시도하지 않는다. 다음 시세가 곧 온다</li>
 * </ul>
 *
 * <p>이벤트 JSON은 받은 문자열 그대로 보낸다. 다시 직렬화하지 않으므로 받는 쪽이 Kafka에 다시 낼 때도
 * 원래 봉투(eventId · traceId)와 같다.
 *
 * <p>시각은 Jackson으로 읽는다. 발행자({@code value-serializer: JsonSerializer})는 Spring Kafka 기본 매퍼를 써서
 * 시각을 숫자(epoch 초.나노)로 쓴다. 문자열로만 읽으면 모든 이벤트를 버린다 (2026-10-16 장애, ADR-0021).
 *
 * <p>버린 것이 조용히 사라지지 않게 1분마다 한 줄로 요약한다.
 */
@Service
@ConditionalOnProperty(prefix = "market.feed-relay", name = "enabled", havingValue = "true")
public class MarketFeedRelay {

    private static final Logger log = LoggerFactory.getLogger(MarketFeedRelay.class);

    /** 요약 로그 주기. 실패 경고도 같은 주기로 모아 찍는다 (야간에 corebank가 503일 때 묶음마다 쌓이지 않게). */
    private static final Duration LOG_INTERVAL = Duration.ofMinutes(1);

    private final CorebankFeedClient client;
    private final ObjectMapper objectMapper;
    private final Duration maxAge;
    private final Clock clock;

    // 리스너 스레드 하나만 부른다. 동기화하지 않는다.
    private Stats stats = new Stats();
    private Instant lastSummaryAt;
    private Instant lastFailureLoggedAt = Instant.MIN;
    private long failedBatchesSinceLastWarn;

    public MarketFeedRelay(CorebankFeedClient client, ObjectMapper objectMapper,
            MarketProperties properties, Clock clock) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.maxAge = properties.price().maxAge();
        this.clock = clock;
        this.lastSummaryAt = clock.instant();
    }

    /**
     * 한 묶음을 보낸다.
     *
     * @return 보낸 이벤트 수. 보내지 않았거나 실패하면 0
     */
    public int relay(List<String> messages) {
        Instant now = clock.instant();
        Map<String, Latest> latest = new LinkedHashMap<>();
        for (String message : messages) {
            Latest candidate = parse(message);
            if (candidate == null) {
                stats.malformed++;
                continue;
            }
            if (candidate.timestamp().plus(maxAge).isBefore(now)) {
                stats.stale++;
                continue;
            }
            latest.merge(candidate.symbol(), candidate,
                    (kept, next) -> next.timestamp().isBefore(kept.timestamp()) ? kept : next);
        }
        int sent = latest.isEmpty() ? 0 : send(now, latest);
        summarizeIfDue(now);
        return sent;
    }

    private int send(Instant now, Map<String, Latest> latest) {
        String body = "{\"events\":["
                + String.join(",", latest.values().stream().map(Latest::json).toList())
                + "]}";
        try {
            Receipt receipt = client.send(body);
            stats.sent += latest.size();
            stats.accepted += receipt.accepted();
            stats.skipped += receipt.skipped();
            return latest.size();
        } catch (RestClientException e) {
            stats.failed += latest.size();
            warnFailure(now, e);
            return 0;
        }
    }

    private Latest parse(String message) {
        try {
            JsonNode payload = objectMapper.readTree(message).path("payload");
            String symbol = payload.path("symbol").asText(null);
            JsonNode timestamp = payload.path("timestamp");
            if (symbol == null || timestamp.isMissingNode() || timestamp.isNull()) {
                return null;
            }
            // 숫자(epoch 초.나노)와 ISO 문자열을 모두 읽는다. 기존 소비자(캔들 · trading)와 같은 규칙이다.
            Instant at = objectMapper.treeToValue(timestamp, Instant.class);
            return at == null ? null : new Latest(symbol, at, message);
        } catch (IOException | RuntimeException e) {
            // 형식이 깨진 메시지다. 건수만 요약에 남긴다. 캔들 소비자가 같은 메시지를 이미 로그로 남긴다.
            return null;
        }
    }

    private void warnFailure(Instant now, RestClientException e) {
        failedBatchesSinceLastWarn++;
        if (lastFailureLoggedAt.plus(LOG_INTERVAL).isAfter(now)) {
            return;
        }
        log.warn("corebank 시세 중계 실패, 최근 {}묶음 버림 ({})", failedBatchesSinceLastWarn, e.getMessage());
        lastFailureLoggedAt = now;
        failedBatchesSinceLastWarn = 0;
    }

    private void summarizeIfDue(Instant now) {
        if (lastSummaryAt.plus(LOG_INTERVAL).isAfter(now) || stats.isEmpty()) {
            return;
        }
        log.info("시세 중계: 보냄 {}건, 버림 오래됨 {}건 · 형식 {}건 · 전송 실패 {}건, corebank 수신 accepted {} · skipped {}",
                stats.sent, stats.stale, stats.malformed, stats.failed, stats.accepted, stats.skipped);
        stats = new Stats();
        lastSummaryAt = now;
    }

    private record Latest(String symbol, Instant timestamp, String json) {
    }

    private static final class Stats {
        long sent;
        long stale;
        long malformed;
        long failed;
        long accepted;
        long skipped;

        boolean isEmpty() {
            return sent + stale + malformed + failed == 0;
        }
    }
}
