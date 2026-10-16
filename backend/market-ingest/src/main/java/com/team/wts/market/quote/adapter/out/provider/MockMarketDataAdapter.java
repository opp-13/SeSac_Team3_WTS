package com.team.wts.market.quote.adapter.out.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.team.wts.market.config.MarketProperties;
import com.team.wts.market.quote.domain.MarketDataListener;
import com.team.wts.market.quote.domain.MarketDataProvider;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;
import com.team.wts.market.quote.domain.OrderBookLevel;
import com.team.wts.market.stock.domain.Stock;
import com.team.wts.market.stock.domain.StockRepository;

/**
 * KIS 자격증명 없이 동작하는 Mock 시세 공급자. (CLAUDE.md §19, §31)
 *
 * <p>전일 종가에서 출발해 틱마다 ±(minChangeRate ~ maxChangeRate) 만큼 움직이는 random walk다.
 * 생성된 가격은 KRX 호가단위에 맞추고 상·하한가(±30%) 안에 가둔다.
 *
 * <p>{@code market.mock.seed}를 주면 같은 순서가 재현되므로 테스트가 난수에 의존하지 않는다.
 */
@Component
@ConditionalOnProperty(name = "market.provider", havingValue = "mock", matchIfMissing = true)
public class MockMarketDataAdapter implements MarketDataProvider, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MockMarketDataAdapter.class);

    /** 한국 주식시장 일일 가격제한폭. */
    private static final BigDecimal PRICE_LIMIT_RATE = new BigDecimal("0.30");
    private static final int ORDER_BOOK_DEPTH = 5;

    private final StockRepository stocks;
    private final MarketDataListener listener;
    private final MarketProperties.Mock config;
    private final List<String> pinnedSymbols;
    private final Random random;

    private final Map<String, MarketPrice> latestPrices = new ConcurrentHashMap<>();
    private final Map<String, Stock> subscribed = new ConcurrentHashMap<>();

    private volatile ScheduledExecutorService scheduler;

    public MockMarketDataAdapter(StockRepository stocks,
                                 MarketDataListener listener,
                                 MarketProperties properties) {
        this.stocks = stocks;
        this.listener = listener;
        this.config = properties.mock();
        this.pinnedSymbols = properties.pinnedSymbols();
        this.random = config.seed() == null ? new Random() : new Random(config.seed());
    }

    // ── MarketDataProvider ─────────────────────────────────

    @Override
    public Optional<MarketPrice> getCurrentPrice(String symbol) {
        return Optional.ofNullable(latestPrices.get(symbol));
    }

    @Override
    public void subscribe(String symbol) {
        // 기준가가 없으면(신규 상장 직후 등) 출발 가격도 가격제한폭도 정할 수 없다. 만들지 않는다.
        stocks.findBySymbol(symbol)
                .filter(stock -> stock.basePrice() != null)
                .ifPresentOrElse(
                        stock -> subscribed.put(symbol, stock),
                        () -> log.warn("Mock 시세를 만들 수 없는 종목: {} (마스터에 없거나 기준가가 없다)", symbol));
    }

    @Override
    public void unsubscribe(String symbol) {
        subscribed.remove(symbol);
    }

    // ── SmartLifecycle ─────────────────────────────────────

    @Override
    public void start() {
        // Phase 8-1은 고정 종목만 구독한다. 수요 기반 구독은 Phase 8-2다 (CLAUDE.md §57.2).
        // 마스터 전체(2,700종목)를 매초 틱하면 Kafka · Valkey만 부풀고 아무도 보지 않는다.
        pinnedSymbols.forEach(this::subscribe);

        long intervalMillis = config.tickInterval().toMillis();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mock-market-tick");
            thread.setDaemon(true);
            return thread;
        });
        // 첫 틱을 한 주기 뒤로 미룬다. 컨텍스트가 완전히 뜨기 전에 이벤트를 내보내지 않기 위해서다.
        executor.scheduleAtFixedRate(
                this::tickQuietly, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        this.scheduler = executor;

        log.info("Mock 시세 스트림 시작: 종목 {}개, 주기 {}ms", subscribed.size(), intervalMillis);
    }

    @Override
    public void stop() {
        ScheduledExecutorService executor = this.scheduler;
        this.scheduler = null;
        if (executor != null) {
            executor.shutdownNow();
            log.info("Mock 시세 스트림 중지");
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }

    private void tickQuietly() {
        try {
            tick();
        } catch (RuntimeException e) {
            // 한 틱이 실패해도 스케줄러가 죽으면 안 된다. 다음 틱에 복구된다.
            log.error("Mock 시세 생성 실패", e);
        }
    }

    /**
     * 구독 중인 모든 종목의 시세를 한 번 생성해 리스너에 전달한다.
     *
     * <p>테스트에서 스케줄러를 기다리지 않고 직접 호출할 수 있게 공개한다.
     */
    public void tick() {
        for (Stock stock : subscribed.values()) {
            MarketPrice price = nextPrice(stock);
            latestPrices.put(stock.symbol(), price);
            listener.onPrice(price);
            listener.onOrderBook(nextOrderBook(price));
        }
    }

    private MarketPrice nextPrice(Stock stock) {
        MarketPrice previous = latestPrices.get(stock.symbol());
        BigDecimal current = previous == null
                ? KrxTickSize.round(stock.basePrice())
                : previous.price();

        BigDecimal next = KrxTickSize.round(clampToDailyLimit(applyRandomWalk(current), stock));

        long volumeIncrement = 100L + random.nextInt(9_900);
        long cumulativeVolume = (previous == null ? 0L : previous.volume()) + volumeIncrement;

        return new MarketPrice(
                stock.symbol(), next, stock.basePrice(), cumulativeVolume, Instant.now());
    }

    private BigDecimal applyRandomWalk(BigDecimal current) {
        BigDecimal span = config.maxChangeRate().subtract(config.minChangeRate());
        BigDecimal magnitude = config.minChangeRate()
                .add(span.multiply(BigDecimal.valueOf(random.nextDouble())));
        BigDecimal signed = random.nextBoolean() ? magnitude : magnitude.negate();
        return current.add(current.multiply(signed));
    }

    /** 상·하한가를 벗어나지 않게 자른다. */
    private BigDecimal clampToDailyLimit(BigDecimal price, Stock stock) {
        BigDecimal base = stock.basePrice();
        BigDecimal limit = base.multiply(PRICE_LIMIT_RATE);
        BigDecimal upper = base.add(limit);
        BigDecimal lower = base.subtract(limit);
        return price.min(upper).max(lower);
    }

    private OrderBook nextOrderBook(MarketPrice price) {
        BigDecimal tick = KrxTickSize.of(price.price());
        List<OrderBookLevel> asks = new ArrayList<>(ORDER_BOOK_DEPTH);
        List<OrderBookLevel> bids = new ArrayList<>(ORDER_BOOK_DEPTH);

        for (int depth = 1; depth <= ORDER_BOOK_DEPTH; depth++) {
            BigDecimal offset = tick.multiply(BigDecimal.valueOf(depth));
            asks.add(new OrderBookLevel(price.price().add(offset), randomQuantity()));
            bids.add(new OrderBookLevel(
                    price.price().subtract(offset).max(BigDecimal.ONE), randomQuantity()));
        }

        return new OrderBook(price.symbol(), asks, bids, price.timestamp());
    }

    private long randomQuantity() {
        return 10L + random.nextInt(990);
    }
}
