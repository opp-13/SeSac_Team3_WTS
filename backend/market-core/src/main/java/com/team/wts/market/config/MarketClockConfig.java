package com.team.wts.market.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간은 주입받아 쓴다. 테스트에서 고정 시각으로 바꿔 끼울 수 있어야 하기 때문이다.
 *
 * <p>Backend는 UTC를 기준으로 한다 (CLAUDE.md §43).
 */
@Configuration
public class MarketClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
