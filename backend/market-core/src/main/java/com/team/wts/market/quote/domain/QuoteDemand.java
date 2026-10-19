package com.team.wts.market.quote.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 지금 누군가 보고 있는 종목. (CLAUDE.md §57.2 수요 상태, §67)
 *
 * <p>market-service(api)가 화면 구독 · 조회를 보고 기록하고, market-ingest가 읽어 그 종목의 모의 시세를 만든다.
 * 다시 만들 수 있는 상태라 Valkey에 둔다 (§18). 기록마다 만료 시각이 있어, 기록하던 인스턴스가 죽어도
 * 유령 수요가 남지 않는다.
 */
public interface QuoteDemand {

    /** 종목들의 수요를 {@code expiresAt}까지로 늘린다. 실패해도 예외를 던지지 않는다 (화면 동작을 막지 않는다). */
    void touch(Collection<String> symbols, Instant expiresAt);

    /** {@code now}에 아직 만료되지 않은 종목. 최근에 기록된 것부터 최대 {@code limit}개. 읽지 못하면 빈 목록. */
    List<String> activeSymbols(Instant now, int limit);
}
