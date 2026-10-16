package com.team.wts.market.quote.domain;

import java.math.BigDecimal;

/** 호가 한 단계. */
public record OrderBookLevel(BigDecimal price, long quantity) {
}
