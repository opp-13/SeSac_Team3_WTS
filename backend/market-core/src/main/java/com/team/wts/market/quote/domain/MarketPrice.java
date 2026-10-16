package com.team.wts.market.quote.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * 한 시점의 현재가. (CLAUDE.md §25 – Server Price Update)
 *
 * <p>금액은 모두 {@link BigDecimal}이다 (§42). 한국 주식 가격은 정수이므로 scale 0으로 다룬다.
 *
 * @param volume 당일 <b>누적</b> 거래량. 틱마다의 증분이 아니다.
 *               누적값이면 이벤트가 중복 전달돼도 캔들 거래량이 부풀지 않는다 (§17).
 */
public record MarketPrice(
        String symbol,
        BigDecimal price,
        BigDecimal previousClose,
        long volume,
        Instant timestamp) {

    /** 전일 대비 등락폭. */
    public BigDecimal change() {
        return price.subtract(previousClose);
    }

    /** 전일 대비 등락률(%). 소수 둘째 자리까지. */
    public BigDecimal changeRate() {
        if (previousClose.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return change()
                .multiply(BigDecimal.valueOf(100))
                .divide(previousClose, 2, RoundingMode.HALF_UP);
    }

    /**
     * 이 시세가 너무 오래됐는지 판단한다. (CLAUDE.md §11)
     *
     * <p>시장가 주문은 stale한 시세로 체결하면 안 된다.
     */
    public boolean isStale(Duration maxAge, Instant now) {
        return timestamp.plus(maxAge).isBefore(now);
    }
}
