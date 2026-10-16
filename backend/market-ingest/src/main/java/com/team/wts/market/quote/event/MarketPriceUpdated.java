package com.team.wts.market.quote.event;

import java.math.BigDecimal;
import java.time.Instant;

import com.team.wts.market.quote.domain.MarketPrice;

/**
 * {@code market.price.updated} 토픽의 payload. (CLAUDE.md §16)
 *
 * <p>도메인 모델({@link MarketPrice})과 분리한 전송 계약이다.
 * 도메인 모델이 바뀌어도 이미 발행된 이벤트의 형식은 유지되어야 하기 때문이다.
 *
 * @param volume 당일 누적 거래량. 증분이 아니라 누적이므로 이벤트가 중복돼도 집계가 부풀지 않는다.
 */
public record MarketPriceUpdated(
        String symbol,
        BigDecimal price,
        BigDecimal previousClose,
        BigDecimal change,
        BigDecimal changeRate,
        long volume,
        Instant timestamp) {

    public static MarketPriceUpdated from(MarketPrice price) {
        return new MarketPriceUpdated(
                price.symbol(),
                price.price(),
                price.previousClose(),
                price.change(),
                price.changeRate(),
                price.volume(),
                price.timestamp());
    }
}
