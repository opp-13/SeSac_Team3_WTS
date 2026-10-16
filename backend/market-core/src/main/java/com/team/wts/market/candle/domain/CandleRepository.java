package com.team.wts.market.candle.domain;

import java.time.Instant;
import java.util.List;

/** 1분봉 저장 포트. (CLAUDE.md §7) */
public interface CandleRepository {

    Candle save(Candle candle);

    /** {@code openTime} 오름차순. */
    List<Candle> findBySymbolAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(String symbol, Instant from);
}
