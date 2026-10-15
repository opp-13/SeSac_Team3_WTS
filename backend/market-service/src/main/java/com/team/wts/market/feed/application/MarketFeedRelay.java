package com.team.wts.market.feed.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.market.config.MarketProperties;
import com.team.wts.market.feed.adapter.out.http.CorebankFeedClient;

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
 */
@Service
@ConditionalOnProperty(prefix = "market.feed-relay", name = "enabled", havingValue = "true")
public class MarketFeedRelay {

    private static final Logger log = LoggerFactory.getLogger(MarketFeedRelay.class);

    /** 야간처럼 corebank가 오래 응답하지 않을 때 같은 경고가 매 묶음마다 쌓이지 않게 한다. */
    private static final Duration FAILURE_LOG_INTERVAL = Duration.ofMinutes(1);

    private final CorebankFeedClient client;
    private final ObjectMapper objectMapper;
    private final Duration maxAge;
    private final Clock clock;

    private Instant lastFailureLoggedAt = Instant.MIN;
    private long droppedSinceLastLog;

    public MarketFeedRelay(CorebankFeedClient client, ObjectMapper objectMapper,
            MarketProperties properties, Clock clock) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.maxAge = properties.price().maxAge();
        this.clock = clock;
    }

    /**
     * 한 묶음을 보낸다. 리스너 스레드 하나가 순서대로 부르므로 동기화하지 않는다.
     *
     * @return 보낸 이벤트 수. 보내지 않았거나 실패하면 0
     */
    public int relay(List<String> messages) {
        Instant now = clock.instant();
        Map<String, Latest> latest = new LinkedHashMap<>();
        for (String message : messages) {
            Latest candidate = parse(message);
            if (candidate == null || candidate.timestamp().plus(maxAge).isBefore(now)) {
                continue;
            }
            latest.merge(candidate.symbol(), candidate,
                    (kept, next) -> next.timestamp().isBefore(kept.timestamp()) ? kept : next);
        }
        if (latest.isEmpty()) {
            return 0;
        }

        String body = "{\"events\":["
                + String.join(",", latest.values().stream().map(Latest::json).toList())
                + "]}";
        try {
            client.send(body);
        } catch (RestClientException e) {
            onFailure(now, latest.size(), e);
            return 0;
        }
        return latest.size();
    }

    private Latest parse(String message) {
        try {
            JsonNode payload = objectMapper.readTree(message).path("payload");
            String symbol = payload.path("symbol").asText(null);
            if (symbol == null) {
                return null;
            }
            return new Latest(symbol, Instant.parse(payload.path("timestamp").asText("")), message);
        } catch (JsonProcessingException | DateTimeParseException e) {
            // 형식이 깨진 시세는 받는 쪽에서도 못 쓴다. 캔들 소비자가 이미 같은 메시지를 로그로 남긴다.
            log.debug("중계하지 않는 시세 이벤트: {}", e.getMessage());
            return null;
        }
    }

    private void onFailure(Instant now, int dropped, RestClientException e) {
        droppedSinceLastLog += dropped;
        if (lastFailureLoggedAt.plus(FAILURE_LOG_INTERVAL).isAfter(now)) {
            return;
        }
        log.warn("corebank 시세 중계 실패, 최근 {}건 버림 ({})", droppedSinceLastLog, e.getMessage());
        lastFailureLoggedAt = now;
        droppedSinceLastLog = 0;
    }

    private record Latest(String symbol, Instant timestamp, String json) {
    }
}
