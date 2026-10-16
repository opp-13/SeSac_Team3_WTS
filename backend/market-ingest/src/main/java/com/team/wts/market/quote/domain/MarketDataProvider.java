package com.team.wts.market.quote.domain;

import java.util.Optional;

/**
 * 시세 공급자 포트. (CLAUDE.md §19)
 *
 * <p>도메인 계층은 KIS 구현체를 직접 참조하지 않는다.
 * 구현은 {@code MockMarketDataAdapter}(Phase 2)와 {@code KisMarketDataAdapter}(Phase 6)다.
 */
public interface MarketDataProvider {

    /** 마지막으로 생성/수신한 현재가. 아직 없으면 빈 값. */
    Optional<MarketPrice> getCurrentPrice(String symbol);

    void subscribe(String symbol);

    void unsubscribe(String symbol);
}
