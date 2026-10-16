package com.team.wts.market.candle.domain;

import java.util.Optional;

/**
 * 아직 확정되지 않은 현재 1분봉을 인스턴스 사이에서 나눠 보는 저장소. (CLAUDE.md §66.4, §18 "현재 1분봉 상태")
 *
 * <p>market-ingest가 집계하면서 쓰고, market-service(api)가 조회 응답의 마지막 봉으로 읽는다. 다시 만들 수 있는 값이다.
 */
public interface CurrentCandleStore {

    void save(Candle current);

    Optional<Candle> find(String symbol);
}
