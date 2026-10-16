package com.team.wts.market.stock.adapter.out.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.team.wts.market.stock.adapter.out.master.KisStockMasterParser.Layout;
import com.team.wts.market.stock.domain.Market;
import com.team.wts.market.stock.domain.Stock;

/**
 * 픽스처는 2026-10 KIS 종목정보 파일에서 경계 사례 줄만 바이트 그대로 잘라낸 것이다 (cp949).
 * 레이아웃이 바뀌면 이 테스트가 먼저 깨진다.
 */
class KisStockMasterParserTest {

    private final KisStockMasterParser parser = new KisStockMasterParser();

    @Test
    @DisplayName("KOSPI: 주권만 읽고 ETF · ETN은 건너뛴다")
    void parsesKospiStocksOnly() throws IOException {
        List<Stock> stocks = parse("kospi_sample.mst", Layout.KOSPI);

        // 픽스처 5줄 = 삼성전자 · 한화3우B · DH오토넥스(거래정지) · ETF 1 · ETN 1
        assertThat(stocks).extracting(Stock::symbol).containsExactly("000300", "00088K", "005930");
        assertThat(stocks).allSatisfy(stock -> assertThat(stock.market()).isEqualTo(Market.KOSPI));
    }

    @Test
    @DisplayName("KOSPI: 종목코드 · 표준코드 · 이름 · 기준가를 제자리에서 읽는다")
    void readsKospiFieldsAtTheRightOffsets() throws IOException {
        Stock samsung = find(parse("kospi_sample.mst", Layout.KOSPI), "005930");

        assertThat(samsung.standardCode()).isEqualTo("KR7005930003");
        assertThat(samsung.name()).isEqualTo("삼성전자");
        assertThat(samsung.basePrice()).isEqualByComparingTo("276000");
        // 전일 기준 시가총액 (억원) = 1,613조. 검색 정렬에 쓴다.
        assertThat(samsung.marketCap()).isEqualTo(16_135_728L);
        assertThat(samsung.tradingHalted()).isFalse();
        assertThat(samsung.tradable()).isTrue();
    }

    @Test
    @DisplayName("영문이 섞인 종목코드(2024년 이후 상장)도 읽는다")
    void readsAlphanumericSymbols() throws IOException {
        assertThat(find(parse("kospi_sample.mst", Layout.KOSPI), "00088K").name()).isEqualTo("한화3우B");
        assertThat(find(parse("kosdaq_sample.mst", Layout.KOSDAQ), "0001A0").name()).isEqualTo("덕양에너젠");
    }

    @Test
    @DisplayName("거래정지 종목은 tradable=false")
    void marksHaltedStocks() throws IOException {
        Stock halted = find(parse("kospi_sample.mst", Layout.KOSPI), "000300");

        assertThat(halted.tradingHalted()).isTrue();
        assertThat(halted.tradable()).isFalse();
    }

    @Test
    @DisplayName("KOSDAQ: 오프셋이 KOSPI와 다르다. 외국주권은 건너뛰고 기준가 0은 null로 둔다")
    void parsesKosdaqWithItsOwnLayout() throws IOException {
        List<Stock> stocks = parse("kosdaq_sample.mst", Layout.KOSDAQ);

        assertThat(stocks).extracting(Stock::symbol)
                .containsExactlyInAnyOrder("247540", "0001A0", "084180", "001840");
        assertThat(find(stocks, "247540").basePrice()).isEqualByComparingTo("114700");
        assertThat(find(stocks, "247540").market()).isEqualTo(Market.KOSDAQ);
        assertThat(find(stocks, "084180").basePrice()).isNull();
        assertThat(find(stocks, "001840").tradingHalted()).isTrue();
    }

    @Test
    @DisplayName("레이아웃이 어긋나면 주권을 하나도 찾지 못한다. 틀린 값을 섞어 넣지 않는다")
    void findsNothingWithWrongLayout() throws IOException {
        // 증권그룹 자리에 ST가 오지 않는다. 빈 결과는 KisStockMasterFiles가 실패로 처리한다.
        assertThat(parse("kospi_sample.mst", Layout.KOSDAQ)).isEmpty();
    }

    @Test
    @DisplayName("줄이 잘려 있으면 멈춘다")
    void failsOnTruncatedLine() {
        byte[] truncated = "005930   KR7005930003삼성전자 ST1".getBytes(KisStockMasterParser.CP949);

        assertThatIllegalStateException()
                .isThrownBy(() -> parser.parse(new ByteArrayInputStream(truncated), Layout.KOSPI))
                .withMessageContaining("1행");
    }

    private List<Stock> parse(String fixture, Layout layout) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/master/" + fixture)) {
            return parser.parse(in, layout);
        }
    }

    private static Stock find(List<Stock> stocks, String symbol) {
        return stocks.stream().filter(stock -> stock.symbol().equals(symbol)).findFirst().orElseThrow();
    }
}
