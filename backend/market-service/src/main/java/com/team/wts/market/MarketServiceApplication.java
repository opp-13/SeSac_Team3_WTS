package com.team.wts.market;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 시세 조회 앱(api). (CLAUDE.md §6.2, §66, ADR-0022)
 *
 * <p>종목 · 현재가 · 호가 · 1분봉 REST와 브라우저 WebSocket을 맡는다. 사용자 수에 맞춰 여러 대로 늘어난다.
 * 시세를 만들지 않는다. market-ingest가 Valkey에 쓴 값을 읽고, Valkey pub/sub로 오는 시세를 자기 세션에 보낸다.
 * KIS · Kafka와 통신하지 않는다.
 */
@SpringBootApplication
public class MarketServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketServiceApplication.class, args);
    }
}
