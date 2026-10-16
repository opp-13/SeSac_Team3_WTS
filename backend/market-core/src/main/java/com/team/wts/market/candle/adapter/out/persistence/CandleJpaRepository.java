package com.team.wts.market.candle.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.team.wts.market.candle.domain.Candle;
import com.team.wts.market.candle.domain.CandleId;
import com.team.wts.market.candle.domain.CandleRepository;

public interface CandleJpaRepository extends CandleRepository, JpaRepository<Candle, CandleId> {
}
