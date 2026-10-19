package com.team.wts.market.quote.adapter.out.cache;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

import com.team.wts.market.quote.domain.QuoteDemand;

/**
 * 시청 수요를 Valkey 정렬 집합 하나에 둔다. (CLAUDE.md §60.2, §67)
 *
 * <pre>
 * market:demand:viewers   member = 종목코드, score = 만료 시각 (epoch ms)
 * </pre>
 *
 * <p>§60.2는 종목마다 키를 두는 그림이었지만, 수요가 있는 종목을 나열하려면 SCAN이 필요하다.
 * 정렬 집합 하나면 "아직 만료되지 않은 것"을 범위 조회 한 번으로 얻는다 (ADR-0023).
 * epoch ms는 2^53보다 작아 double score로 정확히 담긴다.
 */
@Component
public class ValkeyQuoteDemand implements QuoteDemand {

    private static final Logger log = LoggerFactory.getLogger(ValkeyQuoteDemand.class);

    static final String VIEWERS_KEY = "market:demand:viewers";

    private final StringRedisTemplate redis;

    public ValkeyQuoteDemand(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void touch(Collection<String> symbols, Instant expiresAt) {
        if (symbols.isEmpty()) {
            return;
        }
        double score = expiresAt.toEpochMilli();
        Set<TypedTuple<String>> tuples = symbols.stream()
                .map(symbol -> TypedTuple.of(symbol, score))
                .collect(Collectors.toSet());
        try {
            redis.opsForZSet().add(VIEWERS_KEY, tuples);
        } catch (DataAccessException e) {
            log.warn("시세 수요 기록 실패: {}개 ({})", symbols.size(), e.getMessage());
        }
    }

    @Override
    public List<String> activeSymbols(Instant now, int limit) {
        long nowMillis = now.toEpochMilli();
        try {
            // 만료된 것을 먼저 지운다. 지우지 않으면 집합이 한없이 커진다.
            redis.opsForZSet().removeRangeByScore(VIEWERS_KEY, Double.NEGATIVE_INFINITY, nowMillis - 1);
            Set<String> active = redis.opsForZSet()
                    .reverseRangeByScore(VIEWERS_KEY, nowMillis, Double.POSITIVE_INFINITY, 0, limit);
            return active == null ? List.of() : List.copyOf(active);
        } catch (DataAccessException e) {
            log.warn("시세 수요 읽기 실패 ({})", e.getMessage());
            return List.of();
        }
    }
}
