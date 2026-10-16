package com.team.wts.market.quote.adapter.out.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 한국거래소 호가 가격단위. (2023년 개편 기준)
 *
 * <p>Mock 시세가 "80,123원" 같은 실제로 존재할 수 없는 값을 만들지 않게 한다.
 * Phase 3의 지정가 주문 검증에서도 같은 규칙을 쓴다.
 */
public final class KrxTickSize {

    // 경계값(미만)과 그 구간의 호가단위. 오름차순이어야 한다.
    private static final long[][] TABLE = {
            {2_000L, 1L},
            {5_000L, 5L},
            {20_000L, 10L},
            {50_000L, 50L},
            {200_000L, 100L},
            {500_000L, 500L},
    };
    private static final long HIGHEST_TICK = 1_000L;

    private KrxTickSize() {
    }

    /** 해당 가격대의 호가단위. */
    public static BigDecimal of(BigDecimal price) {
        long value = price.longValue();
        for (long[] row : TABLE) {
            if (value < row[0]) {
                return BigDecimal.valueOf(row[1]);
            }
        }
        return BigDecimal.valueOf(HIGHEST_TICK);
    }

    /**
     * 가장 가까운 유효 호가로 맞춘다.
     *
     * <p>반올림 후 가격대가 바뀌어 단위가 달라질 수 있어(예: 49,990 → 50,000) 한 번 더 확인한다.
     */
    public static BigDecimal round(BigDecimal price) {
        BigDecimal rounded = roundTo(price, of(price));
        BigDecimal tickAtRounded = of(rounded);
        return roundTo(rounded, tickAtRounded);
    }

    private static BigDecimal roundTo(BigDecimal price, BigDecimal tick) {
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick).setScale(0, RoundingMode.UNNECESSARY);
    }
}
