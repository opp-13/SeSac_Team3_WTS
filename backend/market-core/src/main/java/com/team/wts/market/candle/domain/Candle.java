package com.team.wts.market.candle.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * 1분봉. (CLAUDE.md §22, §23)
 *
 * @param openTime 해당 분의 시작 시각(UTC). 예: 09:00:00 봉은 09:00:00~09:00:59를 담는다.
 */
@Entity
@Table(name = "candles_1m")
@IdClass(CandleId.class)
public class Candle {

    @Id
    @Column(name = "symbol", length = 20, nullable = false, updatable = false)
    private String symbol;

    @Id
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "open_time", nullable = false, updatable = false)
    private Instant openTime;

    @Column(name = "open", precision = 19, scale = 4, nullable = false)
    private BigDecimal open;

    @Column(name = "high", precision = 19, scale = 4, nullable = false)
    private BigDecimal high;

    @Column(name = "low", precision = 19, scale = 4, nullable = false)
    private BigDecimal low;

    @Column(name = "close", precision = 19, scale = 4, nullable = false)
    private BigDecimal close;

    @Column(name = "volume", nullable = false)
    private long volume;

    /** JPA 전용. */
    protected Candle() {
    }

    public Candle(String symbol, Instant openTime,
                  BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                  long volume) {
        this.symbol = symbol;
        this.openTime = openTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public String symbol() {
        return symbol;
    }

    public Instant openTime() {
        return openTime;
    }

    public BigDecimal open() {
        return open;
    }

    public BigDecimal high() {
        return high;
    }

    public BigDecimal low() {
        return low;
    }

    public BigDecimal close() {
        return close;
    }

    public long volume() {
        return volume;
    }
}
