# ADR-0012: KIS 연동은 설정 한 줄로 갈아 끼운다

- 상태: 채택
- 날짜: 2026-09-22
- 관련: CLAUDE.md §0-4, §19, §20, §31, §47 Phase 6
- 갱신: ADR-0018 — 실전투자(REAL) 환경을 지웠다. 모의투자(vts)만 쓴다
- 갱신: ADR-0023 — Mock 어댑터(`MockMarketDataAdapter`)는 지웠다. Mock 모드에는 실제 시세 공급자가 없고, 결정형 모의 시세가 모든 종목을 만든다. kis 모드에서도 실시간으로 받지 않는 종목은 결정형 모의 시세다

## 맥락

§0-4는 "외부 한국투자증권 API 자격증명이 없어도 Mock Market Mode로 전체 서비스가
동작해야 한다"고 못 박는다. 동시에 Phase 6의 완료조건은 `MARKET_PROVIDER=kis` 로
실제 데이터를 받는 것이다. 두 모드가 공존해야 한다.

## 결정

`MarketDataProvider` 포트(§19) 아래에 구현체 둘을 두고 `market.provider` 프로퍼티로 고른다.

```java
@ConditionalOnProperty(name = "market.provider", havingValue = "mock", matchIfMissing = true)
class MockMarketDataAdapter implements MarketDataProvider, SmartLifecycle { }

@ConditionalOnProperty(name = "market.provider", havingValue = "kis")
class KisMarketDataAdapter implements MarketDataProvider, SmartLifecycle { }
```

§19는 Spring Profile도 대안으로 제시하지만 프로퍼티를 골랐다. Profile은 로깅·데이터소스 등
다른 설정까지 함께 끌고 다니기 쉽고, 여기서 갈아 끼우는 것은 **어댑터 하나**뿐이다.
`.env`의 `MARKET_PROVIDER` 한 줄이 그대로 완료조건의 문장과 같다는 점도 이유다.

**기본값은 `mock`이다** (`matchIfMissing = true`). 자격증명 없이 clone한 팀원이
아무 설정 없이 전체 흐름을 돌려볼 수 있어야 한다 (§0-4).

## 경계가 지켜지는 지점

두 어댑터가 하는 일은 `MarketDataListener.onPrice / onOrderBook` 호출까지다.
그 뒤 Valkey 저장 → Kafka 발행 → WebSocket 브로드캐스트는 `QuoteIngestService`가
**똑같이** 처리한다. 그래서 provider를 바꿔도 1분봉 집계, 지정가 자동 체결,
포트폴리오 평가가 전부 그대로 동작한다.

Phase 6에서 trading-service는 한 줄도 바뀌지 않았다. Valkey 키 형식이 계약이기 때문이다
(ADR-0008).

## 자격증명이 없을 때

`market.provider=kis` 인데 앱키가 비어 있으면 **기동에서 막는다**. 그대로 두면
"인증 실패"만 반복되는 로그가 쌓이고 원인을 찾기 어렵다 (§52).

```
market.provider=kis 인데 KIS_APP_KEY / KIS_APP_SECRET 이 비어 있다.
루트 .env 에 채우거나 MARKET_PROVIDER=mock 으로 되돌린다.
```

## 실전투자와 모의투자

같은 KIS지만 **도메인도 앱키도 완전히 다른 시스템**이다. 섞으면 인증이 실패한다.
`kis.environment` (`vts` | `real`) 하나로 REST·WebSocket 주소가 함께 바뀐다.
주소를 직접 넣어야 하면 `kis.rest.base-url` / `kis.websocket.url` 로 덮어쓴다.

모의투자 도메인도 **국내주식 실시간 체결가·호가를 지원한다**. 실측으로 확인했다.
Phase 6은 모의투자 키로 개발·검증했다.
