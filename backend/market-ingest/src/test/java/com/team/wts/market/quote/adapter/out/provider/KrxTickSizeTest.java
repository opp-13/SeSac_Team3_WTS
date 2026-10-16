package com.team.wts.market.quote.adapter.out.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KrxTickSizeTest {

    @ParameterizedTest
    @DisplayName("가격대별 호가단위를 돌려준다")
    @CsvSource({
            "1500, 1",
            "1999, 1",
            "2000, 5",
            "4999, 5",
            "5000, 10",
            "19999, 10",
            "20000, 50",
            "49999, 50",
            "50000, 100",
            "199999, 100",
            "200000, 500",
            "499999, 500",
            "500000, 1000",
            "1200000, 1000",
    })
    void returnsTickSizeForPriceBand(String price, String expectedTick) {
        assertThat(KrxTickSize.of(new BigDecimal(price)))
                .isEqualByComparingTo(new BigDecimal(expectedTick));
    }

    @ParameterizedTest
    @DisplayName("가장 가까운 유효 호가로 맞춘다")
    @CsvSource({
            "80123, 80100",
            "80160, 80200",
            "45021, 45000",
            "1999.4, 1999",
            "250480, 250500",
    })
    void roundsToNearestValidTick(String raw, String expected) {
        assertThat(KrxTickSize.round(new BigDecimal(raw)))
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    @DisplayName("반올림으로 가격대가 바뀌어도 그 구간의 호가단위를 만족한다")
    void staysValidWhenRoundingCrossesBand() {
        // 49,990은 50 단위 구간이지만 반올림하면 50,000(100 단위 구간)이 된다.
        BigDecimal rounded = KrxTickSize.round(new BigDecimal("49990"));

        assertThat(rounded.remainder(KrxTickSize.of(rounded))).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
