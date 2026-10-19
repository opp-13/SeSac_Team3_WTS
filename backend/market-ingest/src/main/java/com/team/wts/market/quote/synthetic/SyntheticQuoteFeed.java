package com.team.wts.market.quote.synthetic;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.team.wts.market.config.MarketProperties;
import com.team.wts.market.quote.domain.MarketDataListener;
import com.team.wts.market.quote.domain.MarketDataProvider;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.QuoteDemand;
import com.team.wts.market.stock.domain.Stock;
import com.team.wts.market.stock.domain.StockRepository;

/**
 * 실제 시세를 받지 않는 종목에 결정형 모의 시세를 준다. (CLAUDE.md §67, ADR-0023)
 *
 * <pre>
 * 대상 = (고정 종목 ∪ 지금 누군가 보고 있는 종목) − 실제 시세를 받는 종목
 * </pre>
 *
 * <p>값은 {@link SyntheticPriceModel}이 정한다. 이 클래스는 "언제 · 어느 종목을" 내보낼지만 정하고,
 * 내보낸 뒤의 경로(Valkey · Kafka · pub/sub)는 실제 시세와 같다 ({@link MarketDataListener}).
 * 같은 슬롯을 다시 내보내도 값이 바이트 단위로 같으므로 재시작 · 중복이 시세를 바꾸지 않는다.
 *
 * <p>쓰는 쪽은 market-ingest 하나다 (§66.2). api는 수요만 기록하고 값을 계산하지 않는다.
 * Phase 8-2에서 KIS가 수요에 따라 종목을 등록하면 {@link MarketDataProvider#realtimeSymbols()}가 늘어나
 * 그 종목은 여기서 자동으로 빠진다.
 */
@Component
@ConditionalOnProperty(name = "market.synthetic.enabled", havingValue = "true", matchIfMissing = true)
public class SyntheticQuoteFeed implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SyntheticQuoteFeed.class);

    private final QuoteDemand demand;
    private final StockRepository stocks;
    private final MarketDataListener listener;
    private final ObjectProvider<MarketDataProvider> realtime;
    private final Clock clock;
    private final List<String> pinnedSymbols;
    private final MarketProperties.Synthetic config;
    private final SyntheticPriceModel model;

    /** 종목별 마지막으로 내보낸 슬롯. 같은 슬롯을 다시 내보내지 않으려는 것이지 정합성 장치가 아니다. */
    private final Map<String, Long> lastEmittedSlot = new ConcurrentHashMap<>();
    /** 시세를 만들 수 없어 경고한 종목. 매 틱 같은 경고를 남기지 않는다. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private volatile ScheduledExecutorService scheduler;

    public SyntheticQuoteFeed(QuoteDemand demand, StockRepository stocks, MarketDataListener listener,
                              ObjectProvider<MarketDataProvider> realtime, MarketProperties properties,
                              Clock clock) {
        this.demand = demand;
        this.stocks = stocks;
        this.listener = listener;
        this.realtime = realtime;
        this.clock = clock;
        this.pinnedSymbols = properties.pinnedSymbols();
        this.config = properties.synthetic();
        this.model = new SyntheticPriceModel(config.slot(), config.seed());
    }

    /**
     * 시각 {@code now}의 슬롯으로 대상 종목의 시세를 한 번 내보낸다.
     *
     * <p>테스트에서 스케줄러를 기다리지 않고 직접 호출할 수 있게 공개한다.
     */
    public void tick(Instant now) {
        long slot = model.slotOf(now);
        Set<String> targets = targets(now);
        lastEmittedSlot.keySet().retainAll(targets);
        if (targets.isEmpty()) {
            return;
        }

        List<Stock> found = stocks.findBySymbolIn(targets).stream()
                .sorted(Comparator.comparing(Stock::symbol))
                .toList();
        if (found.size() < targets.size()) {
            Set<String> missing = new LinkedHashSet<>(targets);
            found.forEach(stock -> missing.remove(stock.symbol()));
            missing.forEach(symbol -> warnOnce(symbol, "종목 마스터에 없다"));
        }

        for (Stock stock : found) {
            if (stock.basePrice() == null || !stock.listed()) {
                // 기준가가 없으면(신규 상장 직후 등) 출발 가격도 가격제한폭도 정할 수 없다.
                warnOnce(stock.symbol(), stock.basePrice() == null ? "기준가가 없다" : "상장폐지 종목이다");
                continue;
            }
            Long previous = lastEmittedSlot.put(stock.symbol(), slot);
            if (previous != null && previous == slot) {
                continue;
            }
            MarketPrice price = model.price(stock.symbol(), stock.basePrice(), slot);
            listener.onPrice(price);
            listener.onOrderBook(model.orderBook(price, slot));
        }
    }

    /** 고정 종목 먼저, 그다음 최근에 기록된 수요 순. 실제 시세를 받는 종목은 뺀다. */
    private Set<String> targets(Instant now) {
        Set<String> targets = new LinkedHashSet<>(pinnedSymbols);
        targets.addAll(demand.activeSymbols(now, config.maxSymbols()));
        MarketDataProvider provider = realtime.getIfAvailable();
        if (provider != null) {
            targets.removeAll(provider.realtimeSymbols());
        }
        return targets;
    }

    private void warnOnce(String symbol, String reason) {
        if (warned.add(symbol)) {
            log.warn("모의 시세를 만들 수 없는 종목: {} ({})", symbol, reason);
        }
    }

    // ── SmartLifecycle ─────────────────────────────────────

    @Override
    public void start() {
        long intervalMillis = config.tickInterval().toMillis();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "synthetic-quote-tick");
            thread.setDaemon(true);
            return thread;
        });
        // 첫 틱을 한 주기 뒤로 미룬다. 컨텍스트가 완전히 뜨기 전에 이벤트를 내보내지 않기 위해서다.
        executor.scheduleAtFixedRate(this::tickQuietly, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        this.scheduler = executor;
        log.info("모의 시세 시작: 슬롯 {}ms, 주기 {}ms, seed {}, 고정 종목 {}개, 수요 상한 {}개",
                config.slot().toMillis(), intervalMillis, config.seed(), pinnedSymbols.size(), config.maxSymbols());
    }

    @Override
    public void stop() {
        ScheduledExecutorService executor = this.scheduler;
        this.scheduler = null;
        if (executor != null) {
            executor.shutdownNow();
            log.info("모의 시세 중지");
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }

    private void tickQuietly() {
        try {
            tick(clock.instant());
        } catch (RuntimeException e) {
            // 한 틱이 실패해도 스케줄러가 죽으면 안 된다. 다음 틱에 복구된다.
            log.error("모의 시세 생성 실패", e);
        }
    }
}
