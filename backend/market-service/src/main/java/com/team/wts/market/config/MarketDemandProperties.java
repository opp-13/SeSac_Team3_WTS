package com.team.wts.market.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시청 수요 기록. (CLAUDE.md §57.2 수요 상태, §67)
 *
 * <p>화면이 구독 · 조회한 종목을 Valkey에 기록하면 market-ingest가 그 종목의 모의 시세를 만든다.
 * 하트비트 주기({@code market.demand.heartbeat-ms})는 {@code @Scheduled}가 직접 읽는다.
 *
 * @param viewerTtl 기록 하나가 살아 있는 시간. 하트비트 주기보다 넉넉히 길어야 보고 있는 동안 끊기지 않는다.
 *                  화면을 닫은 뒤 이만큼 지나면 모의 시세도 멈춘다
 */
@ConfigurationProperties(prefix = "market.demand")
public record MarketDemandProperties(Duration viewerTtl) {

    public MarketDemandProperties {
        viewerTtl = viewerTtl == null ? Duration.ofSeconds(30) : viewerTtl;
    }
}
