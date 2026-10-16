package com.team.wts.market.stock.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.team.wts.market.stock.domain.Market;
import com.team.wts.market.stock.domain.Stock;
import com.team.wts.market.stock.domain.StockRepository;

/**
 * 종목 마스터 저장소. (CLAUDE.md §57.1)
 *
 * <p>2,700행이라 LIKE 부분일치로 충분하다. 검색 엔진을 들이지 않는다.
 * 대소문자 구분은 컬럼 collation(utf8mb4_0900_ai_ci)이 없앤다.
 */
public interface StockJpaRepository extends StockRepository, JpaRepository<Stock, String> {

    /**
     * 정렬: 종목코드 일치 → 종목코드로 시작 → 이름으로 시작 → 그 밖의 부분일치.
     * 같은 순위 안에서는 시가총액이 큰 종목이 먼저다. "삼성"을 치면 삼성전자가 맨 앞이다.
     * 이름순으로만 두면 영문이 한글보다 앞서 삼성E&amp;A가 먼저 나온다.
     */
    @Override
    @Query(value = """
            select s from Stock s
            where s.listed = true
              and (:market is null or s.market = :market)
              and (:keyword is null
                   or s.symbol like concat('%', :keyword, '%') escape '\\'
                   or s.name like concat('%', :keyword, '%') escape '\\')
            order by
              case when s.symbol = :keyword then 0
                   when s.symbol like concat(:keyword, '%') escape '\\' then 1
                   when s.name like concat(:keyword, '%') escape '\\' then 2
                   else 3 end,
              s.marketCap desc nulls last, s.name, s.symbol
            """,
            countQuery = """
            select count(s) from Stock s
            where s.listed = true
              and (:market is null or s.market = :market)
              and (:keyword is null
                   or s.symbol like concat('%', :keyword, '%') escape '\\'
                   or s.name like concat('%', :keyword, '%') escape '\\')
            """)
    Page<Stock> search(@Param("keyword") String keyword, @Param("market") Market market,
                       Pageable pageable);

    @Override
    @Query("select max(s.syncedAt) from Stock s")
    Optional<Instant> lastSyncedAt();
}
