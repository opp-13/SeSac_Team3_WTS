package com.team.wts.market.quote.synthetic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.team.wts.market.config.MarketProperties;
import com.team.wts.market.quote.domain.MarketDataListener;
import com.team.wts.market.quote.domain.MarketDataProvider;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;
import com.team.wts.market.quote.domain.QuoteDemand;
import com.team.wts.market.stock.domain.Market;
import com.team.wts.market.stock.domain.Stock;
import com.team.wts.market.stock.domain.StockRepository;

/** 모의 시세를 "언제 · 어느 종목에" 내보내는가. (CLAUDE.md §67) */
class SyntheticQuoteFeedTest {

    private static final Instant NOW = Instant.parse("2026-10-19T01:30:00Z");
    private static final List<String> PINNED = List.of("005930", "000660");

    private final Map<String, Stock> master = Map.of(
            "005930", stock("005930", "80000"),
            "000660", stock("000660", "200000"),
            "000020", stock("000020", "9000"),
            "0001A0", stock("0001A0", null));

    private final StockRepository stocks = mock(StockRepository.class);
    private final QuoteDemand demand = mock(QuoteDemand.class);
    private final List<MarketPrice> emitted = new ArrayList<>();
    private final MarketDataListener listener = new MarketDataListener() {
        @Override
        public void onPrice(MarketPrice price) {
            emitted.add(price);
        }

        @Override
        public void onOrderBook(OrderBook orderBook) {
        }
    };

    @BeforeEach
    void setUp() {
        when(stocks.findBySymbolIn(any())).thenAnswer(invocation -> {
            Collection<String> symbols = invocation.getArgument(0);
            return symbols.stream().filter(master::containsKey).map(master::get).toList();
        });
        when(demand.activeSymbols(any(), anyInt())).thenReturn(List.of());
    }

    @Test
    @DisplayName("Mock 모드(실제 공급자 없음)에서는 고정 종목을 만든다")
    void producesPinnedSymbolsWithoutRealProvider() {
        feed(null).tick(NOW);

        assertThat(emittedSymbols()).containsExactlyInAnyOrder("005930", "000660");
    }

    @Test
    @DisplayName("실제 시세를 받는 종목은 만들지 않는다 - 실제와 모의가 섞이지 않는다")
    void skipsRealtimeSymbols() {
        when(demand.activeSymbols(any(), anyInt())).thenReturn(List.of("000020"));

        feed(Set.copyOf(PINNED)).tick(NOW);

        assertThat(emittedSymbols()).containsExactly("000020");
    }

    @Test
    @DisplayName("보고 있는 종목을 더한다. 마스터에 없거나 기준가가 없는 종목은 건너뛴다")
    void addsViewedSymbolsAndSkipsUnknown() {
        when(demand.activeSymbols(any(), anyInt())).thenReturn(List.of("000020", "0001A0", "999999"));

        feed(null).tick(NOW);

        assertThat(emittedSymbols()).containsExactlyInAnyOrder("005930", "000660", "000020");
    }

    @Test
    @DisplayName("같은 슬롯은 다시 내보내지 않고, 슬롯이 넘어가면 내보낸다")
    void emitsOncePerSlot() {
        SyntheticQuoteFeed feed = feed(null);

        feed.tick(NOW);
        feed.tick(NOW.plusMillis(900));
        assertThat(emitted).hasSize(2);

        feed.tick(NOW.plusSeconds(1));
        assertThat(emitted).hasSize(4);
    }

    @Test
    @DisplayName("재시작해도(새 인스턴스) 같은 시각이면 같은 시세다")
    void restartYieldsSameQuotes() {
        feed(null).tick(NOW);
        List<MarketPrice> first = List.copyOf(emitted);
        emitted.clear();

        feed(null).tick(NOW.plusMillis(300));

        assertThat(emitted).containsExactlyElementsOf(first);
    }

    @Test
    @DisplayName("수요 상한을 그대로 넘긴다")
    void passesDemandLimit() {
        feed(null).tick(NOW);

        org.mockito.Mockito.verify(demand).activeSymbols(NOW, 100);
    }

    private SyntheticQuoteFeed feed(Set<String> realtimeSymbols) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (realtimeSymbols != null) {
            MarketDataProvider provider = mock(MarketDataProvider.class);
            when(provider.realtimeSymbols()).thenReturn(realtimeSymbols);
            beans.addBean("provider", provider);
        }
        ObjectProvider<MarketDataProvider> realtime = beans.getBeanProvider(MarketDataProvider.class);
        MarketProperties properties = new MarketProperties("mock", PINNED, null, null, null, null);
        return new SyntheticQuoteFeed(demand, stocks, listener, realtime, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private List<String> emittedSymbols() {
        return emitted.stream().map(MarketPrice::symbol).toList();
    }

    private static Stock stock(String symbol, String basePrice) {
        return Stock.listed(symbol, "KR7" + symbol + "000", symbol, Market.KOSPI,
                basePrice == null ? null : new BigDecimal(basePrice), 1L, false);
    }
}
