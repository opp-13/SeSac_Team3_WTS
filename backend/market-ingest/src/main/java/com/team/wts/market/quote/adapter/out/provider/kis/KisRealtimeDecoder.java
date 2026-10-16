package com.team.wts.market.quote.adapter.out.provider.kis;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;
import com.team.wts.market.quote.domain.OrderBookLevel;

/**
 * KIS 실시간 TR을 도메인 모델로 옮긴다. (CLAUDE.md §20)
 *
 * <p>순수 함수다. 네트워크도 상태도 없다. 필드 위치가 계약의 전부라
 * 실제 수신 데이터로 고정해 두는 것이 유일한 방어다.
 *
 * <p>KIS는 한국 시각으로 시간을 보낸다. 저장은 UTC다 (§43).
 */
public final class KisRealtimeDecoder {

    /** 국내주식 실시간 체결가. */
    public static final String TR_PRICE = "H0STCNT0";
    /** 국내주식 실시간 호가. */
    public static final String TR_ORDER_BOOK = "H0STASP0";

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    // ── H0STCNT0 (47 필드) ──
    private static final int PRICE_FIELDS = 47;
    private static final int P_SYMBOL = 0;
    private static final int P_TIME = 1;
    private static final int P_PRICE = 2;
    /** 전일 대비 부호. 1 상한 / 2 상승 / 3 보합 / 4 하한 / 5 하락 */
    private static final int P_CHANGE_SIGN = 3;
    private static final int P_CHANGE = 4;
    /** 당일 누적 거래량. 증분이 아니라 누적이다 (§17 – 중복 수신에 안전하다). */
    private static final int P_ACCUMULATED_VOLUME = 13;
    private static final int P_BUSINESS_DATE = 33;

    // ── H0STASP0 (63 필드) ──
    private static final int ORDER_BOOK_FIELDS = 63;
    private static final int O_SYMBOL = 0;
    private static final int O_TIME = 1;
    /** 매도호가 1~10. 오름차순(최우선이 가장 낮은 가격). */
    private static final int O_ASK_PRICE = 3;
    /** 매수호가 1~10. 내림차순(최우선이 가장 높은 가격). */
    private static final int O_BID_PRICE = 13;
    private static final int O_ASK_QUANTITY = 23;
    private static final int O_BID_QUANTITY = 33;
    private static final int KIS_DEPTH = 10;

    /** 화면과 체결 판정에 쓰는 호가 단계. KIS는 10단계를 주지만 MVP는 5단계만 쓴다 (§25). */
    private static final int DEPTH = 5;

    private final Clock clock;

    public KisRealtimeDecoder(Clock clock) {
        this.clock = clock;
    }

    /** 체결가 프레임 하나에 여러 체결이 들어 있다. */
    public List<MarketPrice> decodePrices(KisRealtimeFrame frame) {
        List<MarketPrice> prices = new ArrayList<>();
        for (String[] r : frame.records(PRICE_FIELDS)) {
            BigDecimal price = decimal(r[P_PRICE]);
            BigDecimal change = signedChange(r[P_CHANGE_SIGN], r[P_CHANGE]);
            if (price == null || change == null) {
                continue;
            }
            prices.add(new MarketPrice(
                    r[P_SYMBOL],
                    price,
                    // KIS는 전일 종가를 실시간 체결에 직접 싣지 않는다. 대비값에서 되돌린다.
                    price.subtract(change),
                    longValue(r[P_ACCUMULATED_VOLUME]),
                    toInstant(r[P_BUSINESS_DATE], r[P_TIME])));
        }
        return prices;
    }

    public Optional<OrderBook> decodeOrderBook(KisRealtimeFrame frame) {
        String[][] records = frame.records(ORDER_BOOK_FIELDS);
        if (records.length == 0) {
            return Optional.empty();
        }
        String[] r = records[0];
        return Optional.of(new OrderBook(
                r[O_SYMBOL],
                levels(r, O_ASK_PRICE, O_ASK_QUANTITY),
                levels(r, O_BID_PRICE, O_BID_QUANTITY),
                // 호가 TR에는 영업일자가 없다. 장중에만 오므로 오늘 날짜로 본다.
                toInstant(LocalDate.now(clock.withZone(SEOUL)).format(DATE), r[O_TIME])));
    }

    private static List<OrderBookLevel> levels(String[] r, int priceBase, int quantityBase) {
        List<OrderBookLevel> levels = new ArrayList<>(DEPTH);
        for (int i = 0; i < DEPTH && i < KIS_DEPTH; i++) {
            BigDecimal price = decimal(r[priceBase + i]);
            if (price == null || price.signum() <= 0) {
                // 상한가·하한가 부근에는 비어 있는 단계가 생긴다.
                continue;
            }
            levels.add(new OrderBookLevel(price, longValue(r[quantityBase + i])));
        }
        return levels;
    }

    /**
     * 부호를 붙인 전일 대비.
     *
     * <p>KIS는 대비를 절댓값으로 주고 방향을 따로 보낸다.
     */
    private static BigDecimal signedChange(String sign, String value) {
        BigDecimal change = decimal(value);
        if (change == null) {
            return null;
        }
        return switch (sign) {
            case "4", "5" -> change.negate();
            case "3" -> BigDecimal.ZERO;
            default -> change;
        };
    }

    private Instant toInstant(String yyyymmdd, String hhmmss) {
        try {
            LocalDate date = LocalDate.parse(yyyymmdd, DATE);
            LocalTime time = LocalTime.parse(hhmmss, TIME);
            return LocalDateTime.of(date, time).atZone(SEOUL).toInstant();
        } catch (RuntimeException e) {
            // 형식이 어긋나도 시세 자체는 살린다. 수신 시각으로 둔다.
            return clock.instant();
        }
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long longValue(String raw) {
        BigDecimal value = decimal(raw);
        return value == null ? 0L : value.longValue();
    }
}
