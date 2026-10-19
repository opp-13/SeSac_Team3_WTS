package com.team.wts.market.quote.synthetic;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.team.wts.market.quote.domain.KrxTickSize;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;
import com.team.wts.market.quote.domain.OrderBookLevel;
import com.team.wts.market.quote.domain.QuoteSource;

/**
 * 결정형 모의 시세. (CLAUDE.md §67, ADR-0023)
 *
 * <p>시세는 {@code (종목코드, 기준가, 슬롯, seed)}의 순수 함수다. 같은 입력이면 언제 · 어디서 · 몇 번 계산해도
 * 바이트 단위로 같은 값이 나온다. 상태가 없으므로 재시작해도, 새로고침해도, 같은 순간의 값은 바뀌지 않는다.
 * 슬롯이 넘어가면 값이 움직인다.
 *
 * <pre>
 * 슬롯      n = floor(epochMillis / slotMillis). 시세 시각은 슬롯의 시작 시각이다 (지금 시각이 아니다)
 * 등락(bp)  옥타브 넷의 value noise 합. 옥타브마다 주기 경계에서 해시로 [-A, A]를 뽑고 그 사이를 정수 선형보간한다
 *           1시간 ±600bp, 10분 ±200bp, 1분 ±60bp, 5초 ±15bp → 합쳐 최대 ±8.75%
 * 가격      기준가 × (10000 + bp) / 10000 → 상 · 하한가(±30%) 안 → KRX 호가단위
 * 거래량    KST 자정부터 센 슬롯 수 m, 종목별 슬롯당 r주: m·r + (해시 mod r). 하루 안에서 늘기만 하고 자정에 다시 센다
 * 호가      현재가에서 호가단위씩 5단계. 잔량은 (종목, 단계, 슬롯)의 해시
 * </pre>
 *
 * <p>정수와 {@link BigDecimal}만 쓴다. 부동소수점 결과는 플랫폼마다 다를 수 있고 금액에 쓰지 않는다 (§42).
 * 알고리즘이나 상수를 바꾸면 모든 종목의 시세 경로가 바뀐다. 골든 값 테스트가 그것을 잡는다.
 */
public final class SyntheticPriceModel {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 한국 주식시장 일일 가격제한폭 (§59.2). */
    private static final BigDecimal PRICE_LIMIT_RATE = new BigDecimal("0.30");
    private static final int ORDER_BOOK_DEPTH = 5;
    private static final long BASIS_POINTS = 10_000L;

    // 해시 성분 구분자. 같은 슬롯이라도 용도마다 다른 값을 뽑는다.
    private static final long NOISE = 1L;
    private static final long VOLUME_RATE = 2L;
    private static final long VOLUME_JITTER = 3L;
    private static final long ASK = 4L;
    private static final long BID = 5L;

    private record Octave(Duration period, long amplitudeBp) {
    }

    private static final List<Octave> OCTAVES = List.of(
            new Octave(Duration.ofHours(1), 600L),
            new Octave(Duration.ofMinutes(10), 200L),
            new Octave(Duration.ofMinutes(1), 60L),
            new Octave(Duration.ofSeconds(5), 15L));

    private final long slotMillis;
    private final long seed;
    private final long[] periodSlots;

    public SyntheticPriceModel(Duration slot, long seed) {
        if (slot.isNegative() || slot.isZero()) {
            throw new IllegalArgumentException("슬롯 길이는 0보다 커야 한다: " + slot);
        }
        this.slotMillis = slot.toMillis();
        this.seed = seed;
        this.periodSlots = OCTAVES.stream()
                .mapToLong(octave -> Math.max(1L, octave.period().toMillis() / slotMillis))
                .toArray();
    }

    /** 이 시각이 속한 슬롯. */
    public long slotOf(Instant instant) {
        return Math.floorDiv(instant.toEpochMilli(), slotMillis);
    }

    public Instant slotStart(long slot) {
        return Instant.ofEpochMilli(Math.multiplyExact(slot, slotMillis));
    }

    /** 슬롯 {@code slot}의 현재가. 기준가는 전일 종가로도 쓴다 (종목 마스터의 기준가, §57.1). */
    public MarketPrice price(String symbol, BigDecimal basePrice, long slot) {
        long bp = 0L;
        for (int k = 0; k < OCTAVES.size(); k++) {
            bp += valueNoise(symbol, k, slot);
        }
        BigDecimal raw = basePrice.multiply(BigDecimal.valueOf(BASIS_POINTS + bp)).movePointLeft(4);
        BigDecimal price = withinDailyLimit(KrxTickSize.round(raw), basePrice);
        return new MarketPrice(symbol, price, basePrice, cumulativeVolume(symbol, slot), slotStart(slot),
                QuoteSource.SIMULATED);
    }

    /** 그 현재가를 가운데 둔 5단계 호가. 같은 슬롯이면 같은 호가다. */
    public OrderBook orderBook(MarketPrice price, long slot) {
        BigDecimal tick = KrxTickSize.of(price.price());
        List<OrderBookLevel> asks = new ArrayList<>(ORDER_BOOK_DEPTH);
        List<OrderBookLevel> bids = new ArrayList<>(ORDER_BOOK_DEPTH);
        for (int depth = 1; depth <= ORDER_BOOK_DEPTH; depth++) {
            BigDecimal offset = tick.multiply(BigDecimal.valueOf(depth));
            asks.add(new OrderBookLevel(price.price().add(offset), quantity(price.symbol(), ASK, depth, slot)));
            bids.add(new OrderBookLevel(price.price().subtract(offset).max(BigDecimal.ONE),
                    quantity(price.symbol(), BID, depth, slot)));
        }
        return new OrderBook(price.symbol(), asks, bids, price.timestamp());
    }

    /** 옥타브 {@code k}의 슬롯 {@code slot}에서의 값 (bp). 양 끝 격자점 값을 정수로 선형보간한다. */
    private long valueNoise(String symbol, int k, long slot) {
        long period = periodSlots[k];
        long amplitude = OCTAVES.get(k).amplitudeBp();
        long cell = Math.floorDiv(slot, period);
        long offset = slot - cell * period;
        long from = StableHash.symmetric(StableHash.of(seed, symbol, NOISE, k, cell), amplitude);
        long to = StableHash.symmetric(StableHash.of(seed, symbol, NOISE, k, cell + 1), amplitude);
        return from + Math.floorDiv((to - from) * offset, period);
    }

    /**
     * 상 · 하한가 안으로 넣는다. 호가단위로 맞춘 뒤라 한도를 넘으면 한 호가씩 안쪽으로 당긴다.
     * 등락이 최대 ±8.75%라 실제로는 일어나지 않지만, 모델 상수를 바꿔도 한도를 넘지 않게 지킨다.
     */
    private static BigDecimal withinDailyLimit(BigDecimal price, BigDecimal basePrice) {
        BigDecimal limit = basePrice.multiply(PRICE_LIMIT_RATE);
        BigDecimal upper = basePrice.add(limit);
        BigDecimal lower = basePrice.subtract(limit).max(BigDecimal.ONE);
        BigDecimal bounded = price;
        while (bounded.compareTo(upper) > 0) {
            bounded = bounded.subtract(KrxTickSize.of(bounded));
        }
        while (bounded.compareTo(lower) < 0) {
            bounded = bounded.add(KrxTickSize.of(bounded));
        }
        return bounded;
    }

    /**
     * 당일 누적 거래량. 이웃한 두 슬롯의 차이가 r + (지터 차이) ≥ 1 이므로 하루 안에서 줄지 않는다.
     * 누적값이라 같은 슬롯을 두 번 내보내도 캔들 거래량이 부풀지 않는다 (§17).
     */
    private long cumulativeVolume(String symbol, long slot) {
        LocalDate day = slotStart(slot).atZone(KST).toLocalDate();
        long dayStartSlot = slotOf(day.atStartOfDay(KST).toInstant());
        long elapsed = slot - dayStartSlot;
        long ratePerSlot = 50L + StableHash.below(StableHash.of(seed, symbol, VOLUME_RATE), 950L);
        long jitter = StableHash.below(StableHash.of(seed, symbol, VOLUME_JITTER, slot), ratePerSlot);
        return elapsed * ratePerSlot + jitter;
    }

    private long quantity(String symbol, long side, int depth, long slot) {
        return 10L + StableHash.below(StableHash.of(seed, symbol, side, depth, slot), 990L);
    }
}
