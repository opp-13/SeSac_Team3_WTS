package com.team.wts.market.stock.adapter.out.master;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.team.wts.market.config.MarketProperties;
import com.team.wts.market.stock.domain.Market;
import com.team.wts.market.stock.domain.Stock;

class KisStockMasterFilesTest {

    @Test
    @DisplayName("저장소의 스냅샷만으로 전 종목 마스터를 만든다 (네트워크 없음)")
    void buildsFullMasterFromBundledSnapshot() {
        List<Stock> stocks = new KisStockMasterFiles(
                new MarketProperties("mock", List.of(), null, null, null, null)).snapshot();

        // 2026-10 스냅샷: KOSPI 주권 914 + KOSDAQ 주권 1,805. 스냅샷을 갱신하면 숫자는 바뀐다.
        assertThat(stocks.stream().filter(s -> s.market() == Market.KOSPI).count()).isGreaterThan(800);
        assertThat(stocks.stream().filter(s -> s.market() == Market.KOSDAQ).count()).isGreaterThan(1500);
        assertThat(stocks).extracting(Stock::symbol)
                .contains("005930", "000660", "035420", "035720", "005380")
                .doesNotHaveDuplicates();
    }
}
