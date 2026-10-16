package com.team.wts.market.stock.domain;

import java.util.List;

/**
 * 종목 마스터 원천 포트. 구현은 한국투자증권 종목정보 파일이다 (CLAUDE.md §19, §57.1).
 *
 * <p>둘 다 주권만 돌려준다. 실패하면 예외를 던진다. 빈 목록을 성공으로 돌려주지 않는다.
 */
public interface StockMasterSource {

    /** KIS에서 최신 파일을 내려받는다. 네트워크가 필요하다. */
    List<Stock> download();

    /** 저장소에 함께 둔 스냅샷. 네트워크 없이 동작한다 (§0-4). */
    List<Stock> snapshot();
}
