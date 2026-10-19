package com.team.wts.market.quote.application;

import java.time.Clock;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;

import com.team.wts.common.market.StockSymbol;
import com.team.wts.market.config.MarketDemandProperties;
import com.team.wts.market.quote.domain.QuoteDemand;

/**
 * 화면이 보고 있는 종목을 기록한다. (CLAUDE.md §57.2, §67)
 *
 * <p>이 앱은 시세를 만들지 않는다 (§66.2). 수요만 남기고, market-ingest가 그 종목의 모의 시세를 만든다.
 * 그래서 시세 값은 언제나 한 곳에서 계산되고, 이 앱이 몇 대로 늘어나도 엇갈리지 않는다.
 *
 * <p>종목코드 형식이 아닌 값은 버린다. 아무 문자열이나 쌓이면 수요 상한(market.synthetic.max-symbols)을
 * 엉뚱한 값이 차지한다. 형식은 맞는데 없는 종목은 market-ingest가 마스터와 맞춰 보며 건너뛴다.
 */
@Service
public class ViewerDemandRecorder {

    private final QuoteDemand demand;
    private final Clock clock;
    private final MarketDemandProperties properties;

    public ViewerDemandRecorder(QuoteDemand demand, Clock clock, MarketDemandProperties properties) {
        this.demand = demand;
        this.clock = clock;
        this.properties = properties;
    }

    public void record(Collection<String> symbols) {
        List<String> valid = symbols.stream().filter(StockSymbol::isValid).distinct().toList();
        if (!valid.isEmpty()) {
            demand.touch(valid, clock.instant().plus(properties.viewerTtl()));
        }
    }
}
