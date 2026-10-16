package com.team.wts.market.stock.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.team.wts.market.config.MarketProperties;
import com.team.wts.market.stock.domain.Market;
import com.team.wts.market.stock.domain.Stock;
import com.team.wts.market.stock.domain.StockMasterSource;
import com.team.wts.market.stock.domain.StockRepository;

class StockMasterSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    private final StockRepository stocks = mock(StockRepository.class);
    private final StockMasterSource source = mock(StockMasterSource.class);
    private final List<Stock> saved = new ArrayList<>();

    @BeforeEach
    void captureSaves() {
        when(stocks.saveAll(any())).thenAnswer(invocation -> {
            Iterable<Stock> batch = invocation.getArgument(0);
            batch.forEach(saved::add);
            return List.copyOf(saved);
        });
    }

    @Nested
    @DisplayName("기동할 때")
    class OnStartup {

        @Test
        @DisplayName("mock: 비어 있으면 스냅샷으로 채우고 내려받지 않는다")
        void mockLoadsSnapshotWhenEmpty() {
            when(stocks.count()).thenReturn(0L);
            when(source.snapshot()).thenReturn(List.of(stock("005930")));

            service("mock").syncOnStartup();

            verify(source, never()).download();
            assertThat(saved).extracting(Stock::symbol).containsExactly("005930");
        }

        @Test
        @DisplayName("mock: 채워져 있으면 아무것도 하지 않는다 (네트워크도 스냅샷도 쓰지 않는다)")
        void mockKeepsExistingMaster() {
            when(stocks.count()).thenReturn(2_719L);

            service("mock").syncOnStartup();

            verify(source, never()).download();
            verify(source, never()).snapshot();
        }

        @Test
        @DisplayName("kis: 비어 있으면 내려받는다")
        void kisDownloadsWhenEmpty() {
            when(stocks.count()).thenReturn(0L);
            when(source.download()).thenReturn(List.of(stock("005930")));

            service("kis").syncOnStartup();

            verify(source, never()).snapshot();
            assertThat(saved).extracting(Stock::symbol).containsExactly("005930");
        }

        @Test
        @DisplayName("kis: 비어 있는데 내려받지 못하면 스냅샷으로 채운다")
        void kisFallsBackToSnapshotWhenEmpty() {
            when(stocks.count()).thenReturn(0L);
            when(source.download()).thenThrow(new IllegalStateException("network down"));
            when(source.snapshot()).thenReturn(List.of(stock("005930")));

            service("kis").syncOnStartup();

            assertThat(saved).extracting(Stock::symbol).containsExactly("005930");
        }

        @Test
        @DisplayName("kis: 오래된 마스터를 새로 받다가 실패하면 기존 마스터를 그대로 둔다")
        void kisKeepsStaleMasterWhenDownloadFails() {
            when(stocks.count()).thenReturn(2_719L);
            when(stocks.lastSyncedAt()).thenReturn(Optional.of(NOW.minus(Duration.ofDays(3))));
            when(source.download()).thenThrow(new IllegalStateException("network down"));

            service("kis").syncOnStartup();

            verify(source, never()).snapshot();
            verify(stocks, never()).saveAll(any());
        }

        @Test
        @DisplayName("kis: 마스터가 최근 것이면 내려받지 않는다")
        void kisSkipsFreshMaster() {
            when(stocks.count()).thenReturn(2_719L);
            when(stocks.lastSyncedAt()).thenReturn(Optional.of(NOW.minus(Duration.ofHours(1))));

            service("kis").syncOnStartup();

            verify(source, never()).download();
        }
    }

    @Nested
    @DisplayName("반영할 때")
    class Apply {

        @Test
        @DisplayName("새 종목은 넣고, 있던 종목은 고치고, 사라진 종목은 상장폐지로 표시한다")
        void insertsRefreshesAndDelists() {
            Stock existing = stock("005930");
            Stock disappeared = stock("000000");
            when(stocks.findAll()).thenReturn(List.of(existing, disappeared));

            Stock renamed = Stock.listed("005930", "KR7005930003", "삼성전자(바뀐 이름)", Market.KOSPI,
                    new BigDecimal("280000"), 16_500_000L, true);
            var result = service("kis").apply(List.of(renamed, stock("0001A0")), "테스트");

            assertThat(result).isEqualTo(new StockMasterSyncService.SyncResult(1, 1, 1));
            assertThat(saved).extracting(Stock::symbol).containsExactly("0001A0");
            assertThat(saved.get(0).syncedAt()).isEqualTo(NOW);
            assertThat(existing.name()).isEqualTo("삼성전자(바뀐 이름)");
            assertThat(existing.basePrice()).isEqualByComparingTo("280000");
            assertThat(existing.marketCap()).isEqualTo(16_500_000L);
            assertThat(existing.tradingHalted()).isTrue();
            assertThat(existing.syncedAt()).isEqualTo(NOW);
            assertThat(disappeared.listed()).isFalse();
            assertThat(disappeared.tradable()).isFalse();
        }

        @Test
        @DisplayName("다시 나타난 종목은 상장 상태로 되돌린다")
        void relistsReappearingStock() {
            Stock delisted = stock("005930");
            delisted.delist();
            when(stocks.findAll()).thenReturn(List.of(delisted));

            service("kis").apply(List.of(stock("005930")), "테스트");

            assertThat(delisted.listed()).isTrue();
        }

        @Test
        @DisplayName("같은 종목코드가 두 번 오면 어느 쪽이 맞는지 모른다. 반영하지 않는다")
        void rejectsDuplicateSymbols() {
            when(stocks.findAll()).thenReturn(List.of());

            assertThatIllegalStateException()
                    .isThrownBy(() -> service("kis").apply(List.of(stock("005930"), stock("005930")), "테스트"));
            verify(stocks, never()).saveAll(any());
        }
    }

    private StockMasterSyncService service(String provider) {
        MarketProperties properties = new MarketProperties(provider, List.of(), null, null, null, null);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new StockMasterSyncService(stocks, source, properties, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Stock stock(String symbol) {
        return Stock.listed(symbol, "KR7" + symbol + "000", "종목" + symbol, Market.KOSPI,
                new BigDecimal("10000"), 1_000L, false);
    }
}
