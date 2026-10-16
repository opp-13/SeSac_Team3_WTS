package com.team.wts.market.stock.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** 종목 마스터 포트. (CLAUDE.md §7, §57.1) */
public interface StockRepository {

    Optional<Stock> findBySymbol(String symbol);

    /** 종목코드로 여러 개. 상장폐지 종목도 돌려준다 (보유 종목 이름을 보여줘야 한다). */
    List<Stock> findBySymbolIn(Collection<String> symbols);

    /**
     * 상장 종목 검색. 종목코드 또는 이름 부분일치.
     *
     * @param keyword LIKE 특수문자를 이스케이프한 값. null이면 전체
     * @param market  null이면 전체 시장
     */
    Page<Stock> search(String keyword, Market market, Pageable pageable);

    List<Stock> findAll();

    <S extends Stock> List<S> saveAll(Iterable<S> stocks);

    long count();

    /** 마지막 동기화 시각. 비어 있으면 빈 값. */
    Optional<Instant> lastSyncedAt();
}
