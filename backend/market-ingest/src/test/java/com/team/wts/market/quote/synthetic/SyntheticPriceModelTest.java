package com.team.wts.market.quote.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.team.wts.market.quote.domain.KrxTickSize;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;
import com.team.wts.market.quote.domain.OrderBookLevel;
import com.team.wts.market.quote.domain.QuoteSource;

/**
 * 결정형 모의 시세. (CLAUDE.md §67)
 *
 * <p>핵심은 "같은 입력이면 언제나 같은 출력"이다. 골든 값은 알고리즘이 바뀌면 깨지도록 일부러 고정했다.
 * 골든 값이 깨졌다면 모든 종목의 시세 경로가 바뀐 것이다. 의도한 변경이면 ADR-0023에 적고 값을 갱신한다.
 */
class SyntheticPriceModelTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SAMSUNG = "005930";
    private static final BigDecimal SAMSUNG_BASE = new BigDecimal("80000.0000");
    private static final Instant AT = Instant.parse("2026-10-19T01:30:00Z"); // 10:30 KST

    private final SyntheticPriceModel model = new SyntheticPriceModel(Duration.ofSeconds(1), 0L);

    @Test
    @DisplayName("골든 값: 알고리즘 · 상수가 바뀌면 깨진다")
    void goldenValues() {
        long slot = model.slotOf(AT);
        MarketPrice price = model.price(SAMSUNG, SAMSUNG_BASE, slot);
        OrderBook book = model.orderBook(price, slot);

        // 가격 | 누적 거래량 | 매도 1호가 (가격 · 잔량) | 매수 1호가 (가격 · 잔량) | 시세 시각
        String actual = String.join("|",
                price.price().toPlainString(),
                Long.toString(price.volume()),
                book.asks().get(0).price().toPlainString() + "x" + book.asks().get(0).quantity(),
                book.bids().get(0).price().toPlainString() + "x" + book.bids().get(0).quantity(),
                price.timestamp().toString());
        assertThat(actual).isEqualTo(GOLDEN);
    }

    private static final String GOLDEN = "80700|31638759|80800x733|80600x52|2026-10-19T01:30:00Z";

    @Test
    @DisplayName("같은 입력이면 인스턴스가 달라도, 몇 번을 계산해도 같은 시세 · 호가다")
    void sameInputsAlwaysYieldSameQuote() {
        SyntheticPriceModel other = new SyntheticPriceModel(Duration.ofSeconds(1), 0L);
        long slot = model.slotOf(AT);
        MarketPrice expected = model.price(SAMSUNG, SAMSUNG_BASE, slot);
        OrderBook expectedBook = model.orderBook(expected, slot);

        for (int i = 0; i < 10_000; i++) {
            MarketPrice again = other.price(SAMSUNG, SAMSUNG_BASE, slot);
            assertThat(again).isEqualTo(expected);
            assertThat(other.orderBook(again, slot)).isEqualTo(expectedBook);
        }
    }

    @Test
    @DisplayName("같은 슬롯 안의 어느 시각이든 같은 값이고, 시세 시각은 슬롯의 시작이다")
    void valueIsFixedWithinSlotAndStampedAtSlotStart() {
        long slot = model.slotOf(AT.plusMillis(999));
        assertThat(slot).isEqualTo(model.slotOf(AT));

        MarketPrice price = model.price(SAMSUNG, SAMSUNG_BASE, slot);
        assertThat(price.timestamp()).isEqualTo(AT);
        assertThat(price.source()).isEqualTo(QuoteSource.SIMULATED);
        assertThat(price.previousClose()).isEqualByComparingTo(SAMSUNG_BASE);
    }

    @Test
    @DisplayName("시간이 흐르면 값이 움직인다")
    void pricesMoveOverTime() {
        long start = model.slotOf(AT);
        long distinct = java.util.stream.LongStream.range(start, start + 600)
                .mapToObj(slot -> model.price(SAMSUNG, SAMSUNG_BASE, slot).price())
                .distinct()
                .count();
        assertThat(distinct).isGreaterThan(10);
    }

    @Test
    @DisplayName("seed나 종목이 다르면 다른 경로다")
    void seedAndSymbolChangeThePath() {
        SyntheticPriceModel otherSeed = new SyntheticPriceModel(Duration.ofSeconds(1), 1L);
        long start = model.slotOf(AT);
        List<BigDecimal> base = path(model, SAMSUNG, start);
        assertThat(path(otherSeed, SAMSUNG, start)).isNotEqualTo(base);
        assertThat(path(model, "000660", start)).isNotEqualTo(base);
    }

    @Test
    @DisplayName("하루 동안 모든 가격이 유효한 호가단위이고 상 · 하한가(±30%) 안이다")
    void staysOnTickGridWithinDailyLimit() {
        for (String base : List.of("80000.0000", "1990.0000", "4995.0000", "49950.0000", "512000.0000", "150.0000")) {
            BigDecimal basePrice = new BigDecimal(base);
            BigDecimal upper = basePrice.multiply(new BigDecimal("1.3"));
            BigDecimal lower = basePrice.multiply(new BigDecimal("0.7"));
            long start = model.slotOf(AT);
            for (long slot = start; slot < start + 86_400; slot += 7) {
                BigDecimal price = model.price("0001A0", basePrice, slot).price();
                assertThat(price.remainder(KrxTickSize.of(price)))
                        .as("호가단위 위반: %s (기준가 %s)", price, base)
                        .isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(price).isBetween(lower, upper);
            }
        }
    }

    @Test
    @DisplayName("누적 거래량은 하루 안에서 줄지 않고, KST 자정에 처음부터 다시 센다")
    void volumeIsCumulativeWithinKstDay() {
        LocalDate day = LocalDate.of(2026, 10, 19);
        long dayStart = model.slotOf(day.atStartOfDay(KST).toInstant());
        long nextDayStart = model.slotOf(day.plusDays(1).atStartOfDay(KST).toInstant());

        long previous = -1L;
        for (long slot = dayStart; slot < nextDayStart; slot += 13) {
            long volume = model.price(SAMSUNG, SAMSUNG_BASE, slot).volume();
            assertThat(volume).isGreaterThan(previous);
            previous = volume;
        }
        long lastOfDay = model.price(SAMSUNG, SAMSUNG_BASE, nextDayStart - 1).volume();
        long firstOfNextDay = model.price(SAMSUNG, SAMSUNG_BASE, nextDayStart).volume();
        assertThat(firstOfNextDay).isLessThan(lastOfDay).isLessThan(1_000L);
    }

    @Test
    @DisplayName("호가는 현재가에서 호가단위씩 벌어진 5단계다")
    void orderBookLadder() {
        long slot = model.slotOf(AT);
        MarketPrice price = model.price(SAMSUNG, SAMSUNG_BASE, slot);
        OrderBook book = model.orderBook(price, slot);
        BigDecimal tick = KrxTickSize.of(price.price());

        assertThat(book.asks()).hasSize(5);
        assertThat(book.bids()).hasSize(5);
        assertThat(book.asks().get(0).price()).isEqualByComparingTo(price.price().add(tick));
        assertThat(book.bids().get(0).price()).isEqualByComparingTo(price.price().subtract(tick));
        assertThat(book.asks()).extracting(OrderBookLevel::quantity).allSatisfy(q -> assertThat(q).isBetween(10L, 999L));
        assertThat(book.timestamp()).isEqualTo(price.timestamp());
    }

    private static List<BigDecimal> path(SyntheticPriceModel model, String symbol, long start) {
        return java.util.stream.LongStream.range(start, start + 120)
                .mapToObj(slot -> model.price(symbol, SAMSUNG_BASE, slot).price())
                .toList();
    }
}
