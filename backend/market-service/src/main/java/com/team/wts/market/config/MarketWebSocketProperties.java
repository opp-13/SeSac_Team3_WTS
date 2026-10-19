package com.team.wts.market.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 브라우저 WebSocket. (CLAUDE.md §25, §68)
 *
 * @param drainTimeout 종료할 때 DRAIN을 보낸 뒤 클라이언트가 새 연결로 옮겨 가기를 기다리는 최대 시간.
 *                     지나면 남은 세션을 1001로 닫는다. preStop(10s) + 이 값 + graceful shutdown(20s)이
 *                     파드의 terminationGracePeriodSeconds(45s) 안에 들어가야 한다
 */
@ConfigurationProperties(prefix = "market.websocket")
public record MarketWebSocketProperties(Duration drainTimeout) {

    public MarketWebSocketProperties {
        drainTimeout = drainTimeout == null ? Duration.ofSeconds(5) : drainTimeout;
    }
}
