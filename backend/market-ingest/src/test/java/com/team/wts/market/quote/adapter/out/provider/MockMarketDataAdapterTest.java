package com.team.wts.market.quote.adapter.out.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.team.wts.market.config.MarketProperties;
import com.team.wts.market.quote.domain.MarketDataListener;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;
import com.team.wts.market.stock.domain.Market;
import com.team.wts.market.stock.domain.Stock;
import com.team.wts.market.stock.domain.StockRepository;

/**
 * Mock 시세 생성기. (CLAUDE.md §31)
 *
 * <p>모든 테스트가 시드를 고정해 난수에 의존하지 않는다.
 */
class MockMarketDataAdapterTest {

    private static final String SAMSUNG = "005930";
    private static final BigDecimal PREVIOUS_CLOSE = new BigDecimal("80000");
    private static final long SEED = 42L;

    @Test
    @DisplayName("같은 시드면 같은 가격 흐름이 재현된다")
    void isReproducibleWithSameSeed() {
        assertThat(pricesFrom(SEED, 20)).isEqualTo(pricesFrom(SEED, 20));
    }

    @Test
    @DisplayName("다른 시드면 다른 흐름이 나온다")
    void differsWithDifferentSeed() {
        assertThat(pricesFrom(SEED, 20)).isNotEqualTo(pricesFrom(SEED + 1, 20));
    }

    @Test
    @DisplayName("생성된 가격은 모두 유효한 호가단위다")
    void generatesOnlyValidTickPrices() {
        for (BigDecimal price : pricesFrom(SEED, 200)) {
            assertThat(price.remainder(KrxTickSize.of(price)))
                    .as("호가단위 위반: %s", price)
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("가격은 상·하한가(±30%)를 벗어나지 않는다")
    void staysWithinDailyPriceLimit() {
        BigDecimal upper = new BigDecimal("104000");  // 80,000 +30%
        BigDecimal lower = new BigDecimal("56000");   // 80,000 -30%

        // 변동폭을 크게 줘서 빠르게 한계까지 밀어붙인다.
        for (BigDecimal price : pricesFrom(SEED, 500, "0.05", "0.10")) {
            assertThat(price).isBetween(lower, upper);
        }
    }

    @Test
    @DisplayName("거래량은 누적이라 단조 증가한다")
    void accumulatesVolumeMonotonically() {
        Capture capture = tick(SEED, 30);

        long previous = -1L;
        for (MarketPrice price : capture.prices) {
            assertThat(price.volume()).isGreaterThan(previous);
            previous = price.volume();
        }
    }

    @Test
    @DisplayName("현재가와 함께 호가도 만든다")
    void publishesOrderBookAlongsidePrice() {
        Capture capture = tick(SEED, 1);

        assertThat(capture.orderBooks).hasSize(1);
        OrderBook book = capture.orderBooks.get(0);
        BigDecimal price = capture.prices.get(0).price();

        assertThat(book.asks()).hasSize(5);
        assertThat(book.bids()).hasSize(5);
        // 매도 최우선은 현재가보다 높고, 매수 최우선은 낮아야 한다.
        assertThat(book.asks().get(0).price()).isGreaterThan(price);
        assertThat(book.bids().get(0).price()).isLessThan(price);
        // 매도는 오름차순, 매수는 내림차순.
        assertThat(book.asks().get(0).price()).isLessThan(book.asks().get(4).price());
        assertThat(book.bids().get(0).price()).isGreaterThan(book.bids().get(4).price());
    }

    @Test
    @DisplayName("구독을 해제하면 더 이상 시세를 만들지 않는다")
    void stopsGeneratingAfterUnsubscribe() {
        Capture capture = new Capture();
        MockMarketDataAdapter adapter = adapter(capture, SEED, "0.001", "0.005");
        adapter.start();

        adapter.unsubscribe(SAMSUNG);
        adapter.tick();

        assertThat(capture.prices).noneMatch(price -> price.symbol().equals(SAMSUNG));
        adapter.stop();
    }

    @Test
    @DisplayName("getCurrentPrice는 마지막으로 생성한 값을 돌려준다")
    void exposesLastGeneratedPrice() {
        Capture capture = new Capture();
        MockMarketDataAdapter adapter = adapter(capture, SEED, "0.001", "0.005");
        adapter.start();
        adapter.tick();

        MarketPrice last = capture.prices.get(capture.prices.size() - 1);
        assertThat(adapter.getCurrentPrice(last.symbol())).contains(last);
        adapter.stop();
    }

    // ── helpers ────────────────────────────────────────────

    private static List<BigDecimal> pricesFrom(long seed, int ticks) {
        return pricesFrom(seed, ticks, "0.001", "0.005");
    }

    private static List<BigDecimal> pricesFrom(long seed, int ticks, String min, String max) {
        return tick(seed, ticks, min, max).prices.stream().map(MarketPrice::price).toList();
    }

    private static Capture tick(long seed, int ticks) {
        return tick(seed, ticks, "0.001", "0.005");
    }

    private static Capture tick(long seed, int ticks, String min, String max) {
        Capture capture = new Capture();
        MockMarketDataAdapter adapter = adapter(capture, seed, min, max);
        adapter.start();
        for (int i = 0; i < ticks; i++) {
            adapter.tick();
        }
        adapter.stop();
        return capture;
    }

    private static MockMarketDataAdapter adapter(MarketDataListener listener,
                                                 long seed, String min, String max) {
        MarketProperties properties = new MarketProperties(
                "mock",
                List.of(SAMSUNG),
                null,
                new MarketProperties.Mock(
                        // 테스트는 tick()을 직접 부른다. 스케줄러가 끼어들지 않도록 주기를 길게 둔다.
                        Duration.ofHours(1),
                        new BigDecimal(min),
                        new BigDecimal(max),
                        seed),
                new MarketProperties.Price(Duration.ofSeconds(5)),
                null);

        StockRepository stocks = mock(StockRepository.class);
        when(stocks.findBySymbol(SAMSUNG)).thenReturn(Optional.of(
                Stock.listed(SAMSUNG, "KR7005930003", "삼성전자", Market.KOSPI, PREVIOUS_CLOSE, null, false)));
        return new MockMarketDataAdapter(stocks, listener, properties);
    }

    private static final class Capture implements MarketDataListener {

        private final List<MarketPrice> prices = new ArrayList<>();
        private final List<OrderBook> orderBooks = new ArrayList<>();

        @Override
        public synchronized void onPrice(MarketPrice price) {
            prices.add(price);
        }

        @Override
        public synchronized void onOrderBook(OrderBook orderBook) {
            orderBooks.add(orderBook);
        }

        synchronized void clear() {
            prices.clear();
            orderBooks.clear();
        }
    }
}
