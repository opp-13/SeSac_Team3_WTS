package com.team.wts.market.quote.adapter.out.provider.kis;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import com.team.wts.market.config.KisProperties;
import com.team.wts.market.quote.domain.MarketPrice;

/**
 * 현재가 단건 조회. (CLAUDE.md §19 – {@code getCurrentPrice})
 *
 * <p>실시간 스트림은 <b>장중에만</b> 흐른다. 장 시작 전이나 마감 후에 서비스를 띄우면
 * 아무 시세도 없어 화면이 비고 주문도 전부 거절된다. 기동 시 이 API로 마지막 시세를 채운다.
 *
 * <p>응답에 체결 시각이 없어 수신 시각을 timestamp로 쓴다. 그래서 장 마감 후에도
 * 마지막 종가로 주문이 체결된다. 모의투자 학습 목적에는 이 편이 낫다고 보고 그대로 둔다.
 * 장 운영시간 검증은 MVP 범위 밖이다 (§3).
 */
public class KisQuoteRestClient {

    private static final Logger log = LoggerFactory.getLogger(KisQuoteRestClient.class);

    private static final String PATH = "/uapi/domestic-stock/v1/quotations/inquire-price";
    /** 국내주식 현재가 시세 조회. */
    private static final String TR_ID = "FHKST01010100";
    /** J = 주식/ETF/ETN */
    private static final String MARKET_DIVISION = "J";

    private final RestClient rest;
    private final KisAuthClient auth;
    private final KisProperties properties;
    private final Clock clock;

    public KisQuoteRestClient(RestClient rest, KisAuthClient auth, KisProperties properties,
                              Clock clock) {
        this.rest = rest;
        this.auth = auth;
        this.properties = properties;
        this.clock = clock;
    }

    /** 실패하면 빈 값. 시세 한 종목을 못 채워도 나머지 기동을 막지 않는다. */
    public Optional<MarketPrice> fetchPrice(String symbol) {
        try {
            Map<?, ?> body = rest.get()
                    .uri(builder -> builder.path(PATH)
                            .queryParam("FID_COND_MRKT_DIV_CODE", MARKET_DIVISION)
                            .queryParam("FID_INPUT_ISCD", symbol)
                            .build())
                    .header("authorization", "Bearer " + auth.accessToken())
                    .header("appkey", properties.appKey())
                    .header("appsecret", properties.appSecret())
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve()
                    .body(Map.class);

            if (body == null || !"0".equals(body.get("rt_cd"))) {
                log.warn("KIS 현재가 조회 실패: symbol={} rt_cd={} msg={}",
                        symbol, body == null ? null : body.get("rt_cd"),
                        body == null ? null : body.get("msg1"));
                return Optional.empty();
            }
            if (!(body.get("output") instanceof Map<?, ?> output)) {
                return Optional.empty();
            }
            BigDecimal price = decimal(output.get("stck_prpr"));
            BigDecimal previousClose = decimal(output.get("stck_sdpr"));
            if (price == null || previousClose == null) {
                return Optional.empty();
            }
            return Optional.of(new MarketPrice(symbol, price, previousClose,
                    longValue(output.get("acml_vol")), clock.instant()));
        } catch (RuntimeException e) {
            log.warn("KIS 현재가 조회 오류: symbol={} ({})", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    private static BigDecimal decimal(Object raw) {
        if (raw == null || raw.toString().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long longValue(Object raw) {
        BigDecimal value = decimal(raw);
        return value == null ? 0L : value.longValue();
    }
}
