package com.team.wts.market.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.team.wts.market.candle.domain.Candle;
import com.team.wts.market.candle.domain.CandleRepository;
import com.team.wts.market.candle.domain.CurrentCandleStore;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;
import com.team.wts.market.quote.domain.OrderBookLevel;
import com.team.wts.market.quote.domain.QuoteCache;
import com.team.wts.market.stock.domain.Market;
import com.team.wts.market.stock.domain.Stock;
import com.team.wts.market.stock.domain.StockRepository;

/**
 * market-ingest가 쓰는 값을 테스트가 대신 넣는다. (CLAUDE.md §66)
 *
 * <p>이 앱은 시세 · 종목 · 1분봉을 읽기만 한다. 쓰는 쪽(market-ingest)과 같은 저장소 코드(market-core)로 넣으므로
 * 키 · 형식이 어긋나면 이 테스트가 먼저 깨진다.
 *
 * <p>종목은 실제 마스터 일부와 페이지를 넘길 만큼의 가상 종목이다. 시가총액은 검색 순위를 시험하려고 정했다.
 */
@Component
public class MarketDataFixture {

    public static final String SAMSUNG = "005930";
    /** 가상 종목 수. 실제 종목 8개와 합쳐 페이지 두 장(20개씩)을 넘긴다. */
    public static final int SYNTHETIC_STOCKS = 40;

    private final StockRepository stocks;
    private final QuoteCache quotes;
    private final CandleRepository candles;
    private final CurrentCandleStore currentCandles;

    public MarketDataFixture(StockRepository stocks, QuoteCache quotes, CandleRepository candles,
            CurrentCandleStore currentCandles) {
        this.stocks = stocks;
        this.quotes = quotes;
        this.candles = candles;
        this.currentCandles = currentCandles;
    }

    /** 종목 마스터. 이미 넣었으면 다시 넣지 않는다 (컨텍스트와 DB를 테스트 클래스끼리 나눠 쓴다). */
    public void seedStocks() {
        if (stocks.count() > 0) {
            return;
        }
        List<Stock> master = new ArrayList<>(List.of(
                stock(SAMSUNG, "KR7005930003", "삼성전자", Market.KOSPI, "80000", 4_500_000L),
                stock("028050", "KR7028050008", "삼성E&A", Market.KOSPI, "25000", 50_000L),
                stock("207940", "KR7207940008", "삼성바이오로직스", Market.KOSPI, "900000", 700_000L),
                stock("000660", "KR7000660001", "SK하이닉스", Market.KOSPI, "200000", 1_400_000L),
                stock("035420", "KR7035420009", "NAVER", Market.KOSPI, "200000", 300_000L),
                stock("035720", "KR7035720002", "카카오", Market.KOSPI, "40000", 180_000L),
                stock("005380", "KR7005380001", "현대차", Market.KOSPI, "250000", 500_000L),
                stock("0001A0", "KR70001A0008", "덕양에너젠", Market.KOSDAQ, "10000", 1_000L)));
        for (int i = 1; i <= SYNTHETIC_STOCKS; i++) {
            String code = "9%05d".formatted(i);
            master.add(stock(code, "KR7" + code + "000", "테스트종목%02d".formatted(i), Market.KOSDAQ,
                    "5000", (long) (SYNTHETIC_STOCKS - i + 1)));
        }
        Instant now = Instant.now();
        master.forEach(stock -> stock.markSynced(now));
        stocks.saveAll(master);
    }

    public static int seededStockCount() {
        return 8 + SYNTHETIC_STOCKS;
    }

    /** 최신 시세와 5단계 호가. */
    public MarketPrice seedQuote(String symbol, String price) {
        MarketPrice latest = new MarketPrice(symbol, new BigDecimal(price), new BigDecimal("80000"), 123_456L,
                Instant.now());
        quotes.savePrice(latest);
        quotes.saveOrderBook(orderBook(symbol, new BigDecimal(price)));
        return latest;
    }

    /** 확정된 직전 봉(DB)과 진행 중인 봉(Valkey). */
    public void seedCandles(String symbol) {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        candles.save(new Candle(symbol, minute.minus(1, ChronoUnit.MINUTES),
                new BigDecimal("80000"), new BigDecimal("80300"), new BigDecimal("79900"), new BigDecimal("80100"), 50));
        currentCandles.save(new Candle(symbol, minute,
                new BigDecimal("80100"), new BigDecimal("80500"), new BigDecimal("80000"), new BigDecimal("80400"), 30));
    }

    public static OrderBook orderBook(String symbol, BigDecimal mid) {
        List<OrderBookLevel> asks = new ArrayList<>();
        List<OrderBookLevel> bids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            asks.add(new OrderBookLevel(mid.add(BigDecimal.valueOf(100L * i)), 10L * i));
            bids.add(new OrderBookLevel(mid.subtract(BigDecimal.valueOf(100L * i)), 10L * i));
        }
        return new OrderBook(symbol, asks, bids, Instant.now());
    }

    private static Stock stock(String symbol, String standardCode, String name, Market market,
            String basePrice, Long marketCap) {
        return Stock.listed(symbol, standardCode, name, market, new BigDecimal(basePrice), marketCap, false);
    }
}
