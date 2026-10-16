package com.team.wts.market.quote.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketPriceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T01:30:00Z");

    @Test
    @DisplayName("전일 대비 등락폭과 등락률을 계산한다")
    void calculatesChangeAndRate() {
        MarketPrice price = price(new BigDecimal("81000"), new BigDecimal("80000"));

        assertThat(price.change()).isEqualByComparingTo("1000");
        assertThat(price.changeRate()).isEqualByComparingTo("1.25");
    }

    @Test
    @DisplayName("하락이면 등락폭과 등락률이 음수다")
    void reportsNegativeChangeWhenPriceFell() {
        MarketPrice price = price(new BigDecimal("79000"), new BigDecimal("80000"));

        assertThat(price.change()).isEqualByComparingTo("-1000");
        assertThat(price.changeRate()).isEqualByComparingTo("-1.25");
    }

    @Test
    @DisplayName("전일 종가가 0이면 등락률은 0으로 둔다")
    void avoidsDivisionByZero() {
        assertThat(price(new BigDecimal("100"), BigDecimal.ZERO).changeRate())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("maxAge 안이면 stale이 아니다")
    void isFreshWithinMaxAge() {
        MarketPrice price = price(new BigDecimal("80000"), new BigDecimal("80000"));

        assertThat(price.isStale(Duration.ofSeconds(5), NOW.plusSeconds(4))).isFalse();
    }

    @Test
    @DisplayName("maxAge를 넘기면 stale이다")
    void isStaleBeyondMaxAge() {
        MarketPrice price = price(new BigDecimal("80000"), new BigDecimal("80000"));

        assertThat(price.isStale(Duration.ofSeconds(5), NOW.plusSeconds(6))).isTrue();
    }

    private static MarketPrice price(BigDecimal value, BigDecimal previousClose) {
        return new MarketPrice("005930", value, previousClose, 1_000L, NOW);
    }
}
