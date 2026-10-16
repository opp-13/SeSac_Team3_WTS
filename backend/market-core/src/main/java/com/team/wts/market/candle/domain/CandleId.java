package com.team.wts.market.candle.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * {@link Candle}의 복합 기본키. (CLAUDE.md §23 – PK (symbol, open_time))
 *
 * <p>JPA {@code @IdClass}는 인자 없는 생성자를 요구해서 record로 만들 수 없다.
 */
public class CandleId implements Serializable {

    private String symbol;
    private Instant openTime;

    protected CandleId() {
    }

    public CandleId(String symbol, Instant openTime) {
        this.symbol = symbol;
        this.openTime = openTime;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CandleId candleId)) {
            return false;
        }
        return Objects.equals(symbol, candleId.symbol) && Objects.equals(openTime, candleId.openTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, openTime);
    }
}
