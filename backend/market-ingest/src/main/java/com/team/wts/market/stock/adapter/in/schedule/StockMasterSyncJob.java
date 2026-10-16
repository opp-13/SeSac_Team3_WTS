package com.team.wts.market.stock.adapter.in.schedule;

import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.team.wts.market.stock.application.StockMasterSyncService;

/**
 * 종목 마스터 동기화를 언제 돌릴지만 정한다. 무엇을 할지는 {@link StockMasterSyncService}가 정한다.
 *
 * <p>기동 시 동기화는 시세 공급자(Mock · KIS 어댑터, 기본 phase)보다 먼저 끝나야 한다.
 * 공급자가 구독할 종목과 출발 가격을 마스터에서 읽기 때문이다. 그래서 phase를 하나 낮춘다.
 *
 * <p>market-ingest(한 대)에서만 돈다 (§66.2). market-service(api) 파드가 늘어날 때마다 마스터를 다시 받지 않는다.
 */
@Component
public class StockMasterSyncJob implements SmartLifecycle {

    private static final int BEFORE_MARKET_DATA_PROVIDERS = SmartLifecycle.DEFAULT_PHASE - 1;

    private final StockMasterSyncService sync;
    private volatile boolean running;

    public StockMasterSyncJob(StockMasterSyncService sync) {
        this.sync = sync;
    }

    @Override
    public void start() {
        sync.syncOnStartup();
        running = true;
    }

    /** 매 영업일 장 시작 전 (KST). 휴장일에 돌아도 같은 파일을 다시 반영할 뿐이다. */
    @Scheduled(cron = "${market.master.sync-cron:0 0 8 * * MON-FRI}", zone = "Asia/Seoul")
    public void daily() {
        sync.syncDaily();
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return BEFORE_MARKET_DATA_PROVIDERS;
    }
}
