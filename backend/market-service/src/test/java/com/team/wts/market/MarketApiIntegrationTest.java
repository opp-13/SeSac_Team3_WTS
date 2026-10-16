package com.team.wts.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.team.wts.market.support.IntegrationTestBase;
import com.team.wts.market.support.MarketDataFixture;

/**
 * 시세 조회 API (CLAUDE.md §24, §57.1, §66).
 *
 * <p>market-ingest가 써 두는 종목 · 시세 · 1분봉을 테스트가 같은 저장소 코드로 넣고({@link MarketDataFixture}),
 * REST로 읽히는지를 실제 MySQL · Valkey에서 검증한다. 수집 흐름은 market-ingest의 통합 테스트가 맡는다.
 */
class MarketApiIntegrationTest extends IntegrationTestBase {

    private static final String SAMSUNG = MarketDataFixture.SAMSUNG;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MarketDataFixture fixture;

    @BeforeEach
    void seed() {
        fixture.seedStocks();
        fixture.seedQuote(SAMSUNG, "80500");
    }

    @Test
    @DisplayName("종목 마스터 전체가 페이지 객체로 나온다")
    void pagesTheWholeMaster() {
        Map<String, Object> page = get("/api/market/stocks?size=1").getBody();

        assertThat(((Number) page.get("totalElements")).longValue()).isEqualTo(MarketDataFixture.seededStockCount());
        assertThat(items(page)).hasSize(1);
    }

    @Test
    @DisplayName("같은 순위 안에서는 시가총액이 큰 종목이 먼저다. '삼성'을 치면 삼성전자가 맨 앞이다")
    void ranksByMarketCapWithinTheSameMatch() {
        List<Map<String, Object>> found = items(get("/api/market/stocks?keyword=삼성&size=3").getBody());

        // 이름순이었다면 영문이 한글보다 앞서 삼성E&A가 먼저 나온다.
        assertThat(found.get(0).get("symbol")).isEqualTo(SAMSUNG);
        assertThat(items(get("/api/market/stocks?size=1").getBody()).get(0).get("symbol")).isEqualTo(SAMSUNG);
    }

    @Test
    @DisplayName("종목명으로 검색한다. 응답에 마스터 정보가 실린다")
    void searchesByNamePrefixFirst() {
        List<Map<String, Object>> found = items(get("/api/market/stocks?keyword=삼성전자&size=5").getBody());

        assertThat(found.get(0).get("symbol")).isEqualTo(SAMSUNG);
        assertThat(found.get(0)).containsEntry("name", "삼성전자")
                .containsEntry("market", "KOSPI")
                .containsEntry("standardCode", "KR7005930003")
                .containsEntry("tradable", true);
        assertThat(found.get(0).get("basePrice")).isNotNull();
    }

    @Test
    @DisplayName("종목코드로 검색하면 코드가 일치하는 종목이 맨 앞이다")
    void searchesBySymbolExactFirst() {
        List<Map<String, Object>> found = items(get("/api/market/stocks?keyword=000660").getBody());

        assertThat(found.get(0).get("symbol")).isEqualTo("000660");
    }

    @Test
    @DisplayName("영문이 섞인 종목코드도 찾는다. 소문자로 쳐도 찾는다")
    void findsAlphanumericSymbols() {
        assertThat(items(get("/api/market/stocks?keyword=0001a0").getBody()))
                .extracting(stock -> stock.get("symbol")).contains("0001A0");
        assertThat(get("/api/market/stocks/0001A0").getBody()).containsEntry("name", "덕양에너젠");
    }

    @Test
    @DisplayName("시장으로 거른다")
    void filtersByMarket() {
        List<Map<String, Object>> kosdaq = items(get("/api/market/stocks?market=KOSDAQ&size=50").getBody());

        assertThat(kosdaq).isNotEmpty().allSatisfy(stock -> assertThat(stock.get("market")).isEqualTo("KOSDAQ"));
    }

    @Test
    @DisplayName("페이지를 넘기면 겹치지 않는 다음 종목이 온다")
    void pagesDoNotOverlap() {
        List<Object> first = symbols(items(get("/api/market/stocks?page=0&size=20").getBody()));
        List<Object> second = symbols(items(get("/api/market/stocks?page=1&size=20").getBody()));

        assertThat(first).hasSize(20).doesNotContainAnyElementsOf(second);
    }

    @Test
    @DisplayName("검색어의 % · _ 는 와일드카드가 아니라 글자 그대로 찾는다")
    void treatsLikeWildcardsLiterally() {
        Map<String, Object> page = get("/api/market/stocks?keyword=%25").getBody();

        assertThat(((Number) page.get("totalElements")).longValue()).isZero();
    }

    @Test
    @DisplayName("symbols 로 여러 종목을 요청한 순서대로 한 번에 받는다. 없는 코드는 빠진다")
    void looksUpManySymbolsAtOnce() {
        List<Map<String, Object>> found =
                items(get("/api/market/stocks?symbols=035720,005930,999999").getBody());

        assertThat(symbols(found)).containsExactly("035720", SAMSUNG);
    }

    @Test
    @DisplayName("페이지 크기가 범위를 벗어나거나 시장 값이 틀리면 400")
    void rejectsInvalidPaging() {
        assertThat(get("/api/market/stocks?size=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/market/stocks?size=101").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/market/stocks?page=-1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/market/stocks?market=NASDAQ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("없는 종목은 404 SYMBOL_NOT_FOUND")
    void returnsNotFoundForUnknownSymbol() {
        ResponseEntity<Map<String, Object>> response = get("/api/market/stocks/999999");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("SYMBOL_NOT_FOUND");
    }

    @Test
    @DisplayName("현재가는 Valkey에 저장된 최신 시세를 돌려준다")
    void returnsLatestPriceFromCache() {
        ResponseEntity<Map<String, Object>> response = get("/api/market/stocks/" + SAMSUNG + "/price");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("symbol")).isEqualTo(SAMSUNG);
        assertThat(new BigDecimal(body.get("price").toString())).isPositive();
        assertThat(new BigDecimal(body.get("price").toString())).isEqualByComparingTo("80500");
        assertThat(new BigDecimal(body.get("previousClose").toString())).isPositive();
        // change = price - previousClose 가 맞아야 한다.
        assertThat(new BigDecimal(body.get("change").toString()))
                .isEqualByComparingTo(new BigDecimal(body.get("price").toString())
                        .subtract(new BigDecimal(body.get("previousClose").toString())));
        assertThat(((Number) body.get("volume")).longValue()).isPositive();
    }

    @Test
    @DisplayName("호가는 매도 5단계·매수 5단계를 돌려준다")
    void returnsOrderBook() {
        ResponseEntity<Map<String, Object>> response =
                get("/api/market/stocks/" + SAMSUNG + "/orderbook");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody().get("asks")).hasSize(5);
        assertThat((List<?>) response.getBody().get("bids")).hasSize(5);
    }

    @Test
    @DisplayName("1분봉은 확정된 봉(DB) 뒤에 진행 중인 봉(Valkey)을 붙여 준다 (§66.4)")
    void returnsCompletedCandlesAndTheCurrentOne() {
        fixture.seedCandles(SAMSUNG);

        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                "/api/market/stocks/" + SAMSUNG + "/candles?interval=1m",
                org.springframework.http.HttpMethod.GET, null,
                new ParameterizedTypeReference<>() { });

        assertThat(response.getBody()).hasSizeGreaterThanOrEqualTo(2);
        Map<String, Object> candle = response.getBody().get(response.getBody().size() - 1);
        // 마지막 봉은 Valkey의 진행 중인 봉이다 (fixture: 고가 80500, 거래량 30)
        assertThat(new BigDecimal(candle.get("high").toString())).isEqualByComparingTo("80500");
        assertThat(((Number) candle.get("volume")).longValue()).isEqualTo(30);
        BigDecimal high = new BigDecimal(candle.get("high").toString());
        BigDecimal low = new BigDecimal(candle.get("low").toString());
        BigDecimal open = new BigDecimal(candle.get("open").toString());
        BigDecimal close = new BigDecimal(candle.get("close").toString());

        assertThat(high).isGreaterThanOrEqualTo(low);
        assertThat(high).isGreaterThanOrEqualTo(open).isGreaterThanOrEqualTo(close);
        assertThat(low).isLessThanOrEqualTo(open).isLessThanOrEqualTo(close);
    }

    @Test
    @DisplayName("지원하지 않는 interval은 400 VALIDATION_FAILED")
    void rejectsUnsupportedInterval() {
        ResponseEntity<Map<String, Object>> response =
                get("/api/market/stocks/" + SAMSUNG + "/candles?interval=5m");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("limit 범위를 벗어나면 400")
    void rejectsOutOfRangeLimit() {
        assertThat(get("/api/market/stocks/" + SAMSUNG + "/candles?limit=0").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/market/stocks/" + SAMSUNG + "/candles?limit=5000").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Map<String, Object>> get(String path) {
        return rest.exchange(path, org.springframework.http.HttpMethod.GET, null,
                new ParameterizedTypeReference<>() { });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static List<Object> symbols(List<Map<String, Object>> stocks) {
        return stocks.stream().map(stock -> stock.get("symbol")).toList();
    }
}
