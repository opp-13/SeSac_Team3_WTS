package com.team.wts.market.quote.domain;

/**
 * 시세가 어디서 왔는가. (CLAUDE.md §57.2, §62.2, §67)
 *
 * <p>화면은 실제 시세가 아니면 그렇다고 알린다. 모의 시세를 실제처럼 보이게 하지 않는다 (§62.2).
 * Phase 8-2에서 KIS 폴링 · 스냅샷 계층이 들어오면 POLLING · SNAPSHOT이 더해진다.
 */
public enum QuoteSource {

    /** 실제 시세 (KIS 실시간 · REST). */
    REALTIME,

    /** 서비스가 만든 결정형 모의 시세 (§67). */
    SIMULATED
}
