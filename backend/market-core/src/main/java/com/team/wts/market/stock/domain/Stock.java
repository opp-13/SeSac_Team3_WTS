package com.team.wts.market.stock.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * 종목 마스터. (CLAUDE.md §57.1)
 *
 * <p>한국투자증권 종목정보 파일로 동기화한다. 파일에서 사라진 종목은 지우지 않고
 * {@code listed=false}로 둔다. 주문 · 포지션 · 관심종목이 그 종목코드를 계속 참조하기 때문이다.
 *
 * <p>{@link Persistable}은 첫 동기화 성능 때문이다. 종목코드를 직접 키로 쓰므로 Spring Data는
 * 새 행인지 알 수 없어 저장할 때마다 SELECT를 먼저 한다. 2,700행을 처음 넣을 때 그 왕복을 없앤다.
 */
@Entity
@Table(name = "stocks")
public class Stock implements Persistable<String> {

    @Id
    @Column(name = "symbol", length = 20, nullable = false, updatable = false)
    private String symbol;

    @Column(name = "standard_code", length = 12, nullable = false)
    private String standardCode;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "market", length = 10, nullable = false)
    private Market market;

    /** 기준가. 파일에 0으로 오면(신규 상장 직후 등) 모르는 값이라 null이다. */
    @Column(name = "base_price", precision = 19, scale = 4)
    private BigDecimal basePrice;

    /** 전일 기준 시가총액 (억원). 검색 정렬에 쓴다. 모르면 null. */
    @Column(name = "market_cap")
    private Long marketCap;

    @Column(name = "trading_halted", nullable = false)
    private boolean tradingHalted;

    @Column(name = "listed", nullable = false)
    private boolean listed;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @Transient
    private boolean isNew = true;

    /** JPA 전용. */
    protected Stock() {
    }

    private Stock(String symbol, String standardCode, String name, Market market,
                  BigDecimal basePrice, Long marketCap, boolean tradingHalted) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.standardCode = Objects.requireNonNull(standardCode, "standardCode");
        this.name = Objects.requireNonNull(name, "name");
        this.market = Objects.requireNonNull(market, "market");
        this.basePrice = basePrice;
        this.marketCap = marketCap;
        this.tradingHalted = tradingHalted;
        this.listed = true;
    }

    /** 종목정보 파일에서 읽은 상장 종목. 저장 시각은 동기화가 정한다. */
    public static Stock listed(String symbol, String standardCode, String name, Market market,
                               BigDecimal basePrice, Long marketCap, boolean tradingHalted) {
        return new Stock(symbol, standardCode, name, market, basePrice, marketCap, tradingHalted);
    }

    /** 파일의 최신 내용으로 바꾼다. 다시 나타난 종목이면 상장 상태로 되돌린다. */
    public void refreshFrom(Stock latest, Instant at) {
        this.standardCode = latest.standardCode;
        this.name = latest.name;
        this.market = latest.market;
        this.basePrice = latest.basePrice;
        this.marketCap = latest.marketCap;
        this.tradingHalted = latest.tradingHalted;
        this.listed = true;
        this.syncedAt = at;
    }

    /** 새로 넣는 종목의 동기화 시각. */
    public void markSynced(Instant at) {
        this.syncedAt = at;
    }

    /** 파일에서 사라졌다. 상장폐지로 본다. */
    public void delist() {
        this.listed = false;
    }

    /** 주문을 받을 수 있는 종목인가. 규칙 검증 자체는 trading이 한다 (§59.3). */
    public boolean tradable() {
        return listed && !tradingHalted;
    }

    public String symbol() {
        return symbol;
    }

    public String standardCode() {
        return standardCode;
    }

    public String name() {
        return name;
    }

    public Market market() {
        return market;
    }

    public BigDecimal basePrice() {
        return basePrice;
    }

    public Long marketCap() {
        return marketCap;
    }

    public boolean tradingHalted() {
        return tradingHalted;
    }

    public boolean listed() {
        return listed;
    }

    public Instant syncedAt() {
        return syncedAt;
    }

    // ── Persistable ────────────────────────────────────────

    @Override
    public String getId() {
        return symbol;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
