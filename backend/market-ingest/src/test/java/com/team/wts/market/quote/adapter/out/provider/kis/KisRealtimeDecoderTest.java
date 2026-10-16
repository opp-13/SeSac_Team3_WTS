package com.team.wts.market.quote.adapter.out.provider.kis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;

/**
 * 실제 KIS 모의투자에서 받은 프레임으로 고정한다. (CLAUDE.md §20)
 *
 * <p>필드 위치가 계약의 전부다. KIS가 형식을 바꾸면 여기가 먼저 깨져야 한다.
 */
class KisRealtimeDecoderTest {

    /** 2026-09-22 14:06:10 KST 수신. 삼성전자 277,750원 (전일 274,000, +3,750). */
    private static final String PRICE_FRAME = "0|H0STCNT0|001|"
            + "005930^140610^277750^2^3750^1.37^280395.69^283000^283500^276500^278000^277500^5"
            + "^11955000^3352130122500^83251^86830^3579^120.39^5217059^6280658^5^0.53^53.70"
            + "^090023^5^-5250^090048^5^-5750^135824^2^1250^20260922^20^N^47588^70626^794823"
            + "^496479^0.20^17123443^69.82^0^^283000^2";

    private static final String ORDER_BOOK_FRAME = "0|H0STASP0|001|"
            + "005930^140610^0^278000^278500^279000^279500^280000^280500^281000^281500^282000"
            + "^282500^277500^277000^276500^276000^275500^275000^274500^274000^273500^273000"
            + "^47584^39910^40703^41379^71975^49537^74523^100544^208210^120466"
            + "^70626^35559^56541^75952^45226^66236^37696^56909^26021^25718"
            + "^794831^496484^0^0^0^0^457236^-274000^5^-100.00^11955000^13^5^0^0^0^277750^4831^2^2";

    private final KisRealtimeDecoder decoder =
            new KisRealtimeDecoder(Clock.fixed(Instant.parse("2026-09-22T05:06:10Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("실시간 체결가를 도메인 모델로 옮긴다")
    void decodesPrice() {
        KisRealtimeFrame frame = KisRealtimeFrame.parse(PRICE_FRAME).orElseThrow();

        List<MarketPrice> prices = decoder.decodePrices(frame);

        assertThat(prices).hasSize(1);
        MarketPrice price = prices.get(0);
        assertThat(price.symbol()).isEqualTo("005930");
        assertThat(price.price()).isEqualByComparingTo("277750");
        // KIS는 전일 종가를 직접 주지 않는다. 현재가 - 전일대비로 되돌린다.
        assertThat(price.previousClose()).isEqualByComparingTo("274000");
        assertThat(price.change()).isEqualByComparingTo("3750");
        assertThat(price.changeRate()).isEqualByComparingTo("1.37");
        // 당일 누적 거래량 (증분이 아니다)
        assertThat(price.volume()).isEqualTo(11_955_000L);
        // 14:06:10 KST = 05:06:10 UTC (§43)
        assertThat(price.timestamp()).isEqualTo(Instant.parse("2026-09-22T05:06:10Z"));
    }

    @Test
    @DisplayName("하락은 전일대비 부호로 구분한다")
    void decodesFallingPrice() {
        // 부호 5 = 하락. 현재가 270,000 / 대비 4,000 → 전일 종가 274,000
        String frame = PRICE_FRAME
                .replace("^277750^2^3750^", "^270000^5^4000^");

        MarketPrice price = decoder.decodePrices(KisRealtimeFrame.parse(frame).orElseThrow()).get(0);

        assertThat(price.previousClose()).isEqualByComparingTo("274000");
        assertThat(price.change()).isEqualByComparingTo("-4000");
    }

    @Test
    @DisplayName("보합은 전일 종가와 같다")
    void decodesUnchangedPrice() {
        String frame = PRICE_FRAME.replace("^277750^2^3750^", "^274000^3^0^");

        MarketPrice price = decoder.decodePrices(KisRealtimeFrame.parse(frame).orElseThrow()).get(0);

        assertThat(price.previousClose()).isEqualByComparingTo("274000");
        assertThat(price.change()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("한 프레임에 여러 체결이 들어와도 모두 읽는다")
    void decodesMultipleRecords() {
        String body = PRICE_FRAME.substring(PRICE_FRAME.indexOf("|001|") + 5);
        KisRealtimeFrame frame =
                KisRealtimeFrame.parse("0|H0STCNT0|002|" + body + "^" + body).orElseThrow();

        assertThat(decoder.decodePrices(frame)).hasSize(2);
    }

    @Test
    @DisplayName("실시간 호가를 5단계까지 옮긴다")
    void decodesOrderBook() {
        KisRealtimeFrame frame = KisRealtimeFrame.parse(ORDER_BOOK_FRAME).orElseThrow();

        OrderBook orderBook = decoder.decodeOrderBook(frame).orElseThrow();

        assertThat(orderBook.symbol()).isEqualTo("005930");
        // 매도호가는 최우선(가장 낮은 가격)부터
        assertThat(orderBook.asks()).hasSize(5);
        assertThat(orderBook.asks().get(0).price()).isEqualByComparingTo("278000");
        assertThat(orderBook.asks().get(0).quantity()).isEqualTo(47_584L);
        assertThat(orderBook.asks().get(4).price()).isEqualByComparingTo("280000");
        // 매수호가는 최우선(가장 높은 가격)부터
        assertThat(orderBook.bids()).hasSize(5);
        assertThat(orderBook.bids().get(0).price()).isEqualByComparingTo("277500");
        assertThat(orderBook.bids().get(0).quantity()).isEqualTo(70_626L);
        assertThat(orderBook.bids().get(4).price()).isEqualByComparingTo("275500");
        assertThat(orderBook.timestamp()).isEqualTo(Instant.parse("2026-09-22T05:06:10Z"));
    }

    @Test
    @DisplayName("비어 있는 호가 단계는 건너뛴다")
    void skipsEmptyLevels() {
        // 상한가 부근에는 매도호가가 0으로 온다.
        String frame = ORDER_BOOK_FRAME.replace("^278000^278500^", "^0^0^");

        OrderBook orderBook = decoder.decodeOrderBook(KisRealtimeFrame.parse(frame).orElseThrow())
                .orElseThrow();

        assertThat(orderBook.asks()).hasSize(3);
        assertThat(orderBook.asks().get(0).price()).isEqualByComparingTo("279000");
    }

    @Test
    @DisplayName("숫자가 아닌 값이 섞여도 그 레코드만 버린다")
    void skipsUnparsableRecord() {
        String frame = PRICE_FRAME.replace("^277750^2^3750^", "^^2^^");

        assertThat(decoder.decodePrices(KisRealtimeFrame.parse(frame).orElseThrow())).isEmpty();
    }
}
