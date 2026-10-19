package com.team.wts.market.quote.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.team.wts.market.config.MarketDemandProperties;
import com.team.wts.market.quote.domain.QuoteDemand;

/** 시청 수요 기록. (CLAUDE.md §67) */
class ViewerDemandRecorderTest {

    private static final Instant NOW = Instant.parse("2026-10-19T01:30:00Z");

    private final QuoteDemand demand = mock(QuoteDemand.class);
    private final ViewerDemandRecorder recorder = new ViewerDemandRecorder(demand,
            Clock.fixed(NOW, ZoneOffset.UTC), new MarketDemandProperties(Duration.ofSeconds(30)));

    @Test
    @DisplayName("종목코드를 지금부터 TTL 뒤까지로 기록한다. 중복은 한 번만")
    void touchesWithTtl() {
        recorder.record(List.of("005930", "0001A0", "005930"));

        verify(demand).touch(List.of("005930", "0001A0"), NOW.plusSeconds(30));
    }

    @Test
    @DisplayName("종목코드 형식이 아닌 값은 버린다 - 수요 상한을 엉뚱한 값이 차지하지 않게")
    void dropsMalformedSymbols() {
        recorder.record(List.of("../x", "5930", "abcdef", "005930"));

        verify(demand).touch(List.of("005930"), NOW.plusSeconds(30));
    }

    @Test
    @DisplayName("남는 것이 없으면 Valkey에 가지 않는다")
    void skipsWhenNothingValid() {
        recorder.record(List.of("bad"));

        verify(demand, never()).touch(any(), any());
    }
}
