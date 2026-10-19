package com.team.wts.market.quote.adapter.in.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.QuoteSource;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 현재가 응답. WebSocket PRICE 메시지와 같은 필드 구성이다 (CLAUDE.md §25).
 *
 * <p>timestamp는 UTC다. 프론트엔드가 Asia/Seoul로 표시한다 (§43).
 */
public record PriceResponse(
        @Schema(description = "종목코드", example = "005930")
        String symbol,
        @Schema(description = "현재가 (원)", example = "79800")
        BigDecimal price,
        @Schema(description = "전일 종가 (원)", example = "80000")
        BigDecimal previousClose,
        @Schema(description = "전일 대비 (원) = price - previousClose", example = "-200")
        BigDecimal change,
        @Schema(description = "전일 대비 등락률 (%), 소수 둘째 자리", example = "-0.25")
        BigDecimal changeRate,
        @Schema(description = "당일 누적 거래량 (주). 틱 증분이 아니다", example = "159379")
        long volume,
        @Schema(description = "시세 시각 (UTC, ISO-8601)", example = "2026-09-22T08:12:44.101Z")
        Instant timestamp,
        @Schema(description = "시세 출처. REALTIME = 실제 시세(KIS), SIMULATED = 서비스가 만든 결정형 모의 시세. "
                + "모의 시세는 같은 시각이면 언제 조회해도 같은 값이다", example = "REALTIME")
        QuoteSource source) {

    public static PriceResponse from(MarketPrice source) {
        return new PriceResponse(source.symbol(), source.price(), source.previousClose(),
                source.change(), source.changeRate(), source.volume(), source.timestamp(), source.source());
    }
}
