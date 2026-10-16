package com.team.wts.market.stock.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.wts.market.config.MarketProperties;
import com.team.wts.market.stock.domain.Stock;
import com.team.wts.market.stock.domain.StockMasterSource;
import com.team.wts.market.stock.domain.StockRepository;

/**
 * 종목 마스터 동기화. (CLAUDE.md §57.1)
 *
 * <pre>
 *            비어 있음                    채워져 있음
 * mock      스냅샷                       그대로 (네트워크를 쓰지 않는다, §0-4)
 * kis       다운로드 → 실패하면 스냅샷   오래됐으면 다운로드 → 실패하면 그대로
 * </pre>
 *
 * <p>다운로드에 실패해도 기존 마스터를 지운 적은 없다. 마스터가 비면 검색 · 주문이 전부 멈추기 때문이다.
 *
 * <p>트랜잭션은 반영({@link #apply})에만 건다. 내려받는 동안 DB 연결을 붙잡지 않는다.
 */
@Service
public class StockMasterSyncService {

    private static final Logger log = LoggerFactory.getLogger(StockMasterSyncService.class);

    private final StockRepository stocks;
    private final StockMasterSource source;
    private final MarketProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public StockMasterSyncService(StockRepository stocks, StockMasterSource source,
            MarketProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.stocks = stocks;
        this.source = source;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 기동할 때. 시세 공급자가 구독할 종목을 찾을 수 있게 그보다 먼저 돈다. */
    public void syncOnStartup() {
        boolean empty = stocks.count() == 0;
        if (properties.kis() && (empty || stale()) && tryDownload()) {
            return;
        }
        if (empty) {
            apply(source.snapshot(), "스냅샷");
        }
    }

    /** 매 영업일 장 시작 전. kis 모드에서만 내려받는다. */
    public void syncDaily() {
        if (properties.kis()) {
            tryDownload();
        }
    }

    private boolean stale() {
        return stocks.lastSyncedAt()
                .map(at -> at.isBefore(clock.instant().minus(properties.master().refreshAfter())))
                .orElse(true);
    }

    /** 내려받은 파일이 깨져서 반영에 실패해도 다운로드 실패와 같게 다룬다. 기존 마스터를 지킨다. */
    private boolean tryDownload() {
        try {
            apply(source.download(), "KIS 다운로드");
            return true;
        } catch (RuntimeException e) {
            log.warn("종목 마스터 다운로드 · 반영 실패. 기존 마스터를 그대로 쓴다: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 최신 목록을 반영한다. 새 종목은 넣고, 있던 종목은 고치고, 목록에서 사라진 종목은 상장폐지로 표시한다.
     *
     * @return 반영 결과
     */
    public SyncResult apply(List<Stock> latest, String origin) {
        if (latest.isEmpty()) {
            throw new IllegalArgumentException("반영할 종목이 없다: " + origin);
        }
        SyncResult result = transaction.execute(status -> applyInTransaction(latest));
        log.info("종목 마스터 동기화 ({}): 신규 {} · 갱신 {} · 상장폐지 {} → 상장 종목 {}개",
                origin, result.added(), result.refreshed(), result.delisted(),
                result.added() + result.refreshed());
        return result;
    }

    private SyncResult applyInTransaction(List<Stock> latest) {
        Map<String, Stock> current = stocks.findAll().stream()
                .collect(Collectors.toMap(Stock::symbol, Function.identity()));
        Instant now = clock.instant();

        Set<String> seen = new HashSet<>();
        List<Stock> added = new ArrayList<>();
        int refreshed = 0;
        for (Stock stock : latest) {
            if (!seen.add(stock.symbol())) {
                // KOSPI와 KOSDAQ 파일에 같은 종목이 있으면 어느 쪽이 맞는지 알 수 없다. 반영하지 않는다.
                throw new IllegalStateException("종목코드가 중복됐다: " + stock.symbol());
            }
            Stock existing = current.get(stock.symbol());
            if (existing == null) {
                stock.markSynced(now);
                added.add(stock);
            } else {
                existing.refreshFrom(stock, now);
                refreshed++;
            }
        }

        int delisted = 0;
        for (Stock existing : current.values()) {
            if (existing.listed() && !seen.contains(existing.symbol())) {
                existing.delist();
                delisted++;
            }
        }
        stocks.saveAll(added);
        return new SyncResult(added.size(), refreshed, delisted);
    }

    public record SyncResult(int added, int refreshed, int delisted) {
    }
}
