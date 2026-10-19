package com.team.wts.market.quote.adapter.in.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.team.wts.market.quote.adapter.in.web.dto.OrderBookResponse;
import com.team.wts.market.quote.adapter.in.web.dto.PriceResponse;
import com.team.wts.market.quote.application.QuoteQueryService;
import com.team.wts.common.error.ApiErrorCodes;
import com.team.wts.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 시세 API. (CLAUDE.md §24) */
@Tag(name = "시세", description = "현재가와 호가. 인증 불필요")
@SecurityRequirements
@RestController
@RequestMapping("/api/market/stocks/{symbol}")
public class QuoteController {

    private final QuoteQueryService quotes;

    public QuoteController(QuoteQueryService quotes) {
        this.quotes = quotes;
    }

    @GetMapping("/price")
    @Operation(summary = "현재가 조회", description = "최신 현재가를 돌려준다. 필드 구성은 WebSocket PRICE 메시지와 같다. 실시간 갱신이 필요하면 WebSocket(/ws/market)을 구독한다. 실제 시세를 받지 않는 종목은 조회 · 구독하면 1초 안에 결정형 모의 시세(source=SIMULATED)가 생긴다. 그 전까지와 아직 시세가 한 번도 들어오지 않은 종목은 503이다.")
    @ApiErrorCodes({ErrorCode.SYMBOL_NOT_FOUND, ErrorCode.MARKET_PRICE_UNAVAILABLE})
    public PriceResponse price(@Parameter(description = "종목코드 (6자리)", example = "005930") @PathVariable String symbol) {
        return PriceResponse.from(quotes.getPrice(symbol));
    }

    @GetMapping("/orderbook")
    @Operation(summary = "호가 조회", description = "최신 매도·매수 호가를 돌려준다. 매도호가는 낮은 가격부터, 매수호가는 높은 가격부터 정렬한다.")
    @ApiErrorCodes({ErrorCode.SYMBOL_NOT_FOUND, ErrorCode.MARKET_PRICE_UNAVAILABLE})
    public OrderBookResponse orderBook(@Parameter(description = "종목코드 (6자리)", example = "005930") @PathVariable String symbol) {
        return OrderBookResponse.from(quotes.getOrderBook(symbol));
    }
}
