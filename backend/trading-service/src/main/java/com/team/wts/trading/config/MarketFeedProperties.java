package com.team.wts.trading.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * channel에서 중계해 오는 시세의 수신 설정. (CLAUDE.md §65.5, ADR-0021)
 *
 * <p>로컬과 한 클러스터 배포는 market-service와 같은 Valkey · Kafka를 쓰므로 끈다.
 *
 * @param enabled      수신 경로({@code POST /internal/market/prices})를 연다
 * @param secret       서명 키. channel market-service와 같은 값이다 (Secrets Manager wts/corebank/market-feed)
 * @param maxClockSkew 서명 시각의 허용 오차. 이보다 오래된 요청은 서명이 맞아도 거절한다
 */
@ConfigurationProperties(prefix = "trading.market-feed")
public record MarketFeedProperties(boolean enabled, String secret, Duration maxClockSkew) {

    public MarketFeedProperties {
        maxClockSkew = maxClockSkew == null ? Duration.ofSeconds(60) : maxClockSkew;
        // 켰는데 키가 없으면 모든 요청이 401이 된다. 원인을 찾기 어렵기 전에 기동에서 막는다 (§52).
        if (enabled && (secret == null || secret.isBlank())) {
            throw new IllegalArgumentException(
                    "trading.market-feed.enabled=true 인데 MARKET_FEED_SECRET 이 비어 있다.");
        }
    }
}
