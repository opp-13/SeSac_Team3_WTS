package com.team.wts.market;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 시세 수집 앱. (CLAUDE.md §6.2, §66, ADR-0022)
 *
 * <p>한국투자증권 OpenAPI와 직접 통신하는 유일한 앱이다. KIS 연결은 앱키당 하나라 <b>한 대만 돈다</b>.
 * 받은 시세를 Valkey(최신값) · Kafka({@code market.price.updated}) · Valkey pub/sub(WebSocket 송신용)로 내보내고,
 * 종목 마스터 동기화 · 1분봉 집계 · corebank 중계(§65)를 맡는다.
 *
 * <p>사용자 요청은 market-service(api)가 받는다. trading-service와는 Kafka/Valkey로만 연결된다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MarketIngestApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketIngestApplication.class, args);
    }
}
