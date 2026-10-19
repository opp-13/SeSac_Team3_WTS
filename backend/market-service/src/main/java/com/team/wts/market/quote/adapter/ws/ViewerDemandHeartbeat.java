package com.team.wts.market.quote.adapter.ws;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.team.wts.market.quote.application.ViewerDemandRecorder;

/**
 * 이 인스턴스의 WebSocket 세션들이 구독 중인 종목의 수요를 주기적으로 늘린다. (CLAUDE.md §57.2, §67)
 *
 * <p>구독을 풀거나 세션이 끊겨도 따로 지우지 않는다. 다른 세션 · 다른 인스턴스가 아직 보고 있을 수 있어서다.
 * 갱신이 멈추면 수요 기록의 만료 시각({@code market.demand.viewer-ttl})이 지나 저절로 사라진다.
 * 이 인스턴스가 죽어도 마찬가지라 유령 수요가 남지 않는다.
 */
@Component
public class ViewerDemandHeartbeat {

    private final MarketSessionRegistry registry;
    private final ViewerDemandRecorder recorder;

    public ViewerDemandHeartbeat(MarketSessionRegistry registry, ViewerDemandRecorder recorder) {
        this.registry = registry;
        this.recorder = recorder;
    }

    @Scheduled(fixedRateString = "${market.demand.heartbeat-ms:10000}",
            initialDelayString = "${market.demand.heartbeat-ms:10000}")
    public void refresh() {
        recorder.record(registry.subscribedSymbols());
    }
}
