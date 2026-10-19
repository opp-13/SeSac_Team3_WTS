package com.team.wts.market.quote.domain;

import java.util.Optional;
import java.util.Set;

/**
 * 실제 시세 공급자 포트. (CLAUDE.md §19)
 *
 * <p>도메인 계층은 KIS 구현체를 직접 참조하지 않는다. 구현은 {@code KisMarketDataAdapter}(Phase 6)뿐이다.
 * Mock 모드에는 실제 시세 공급자가 없다. 모든 종목을 결정형 모의 시세({@code SyntheticQuoteFeed})가 만든다 (§67).
 */
public interface MarketDataProvider {

    /**
     * 지금 실제 시세를 받는 종목. 모의 시세는 이 종목들을 만들지 않는다 (§67).
     * 실제와 모의가 한 종목에 섞이지 않게 하는 경계다.
     */
    Set<String> realtimeSymbols();

    /** 마지막으로 생성/수신한 현재가. 아직 없으면 빈 값. */
    Optional<MarketPrice> getCurrentPrice(String symbol);

    void subscribe(String symbol);

    void unsubscribe(String symbol);
}
