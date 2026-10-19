# CLAUDE.md
# Mock Stock Trading WTS MVP – Claude Code Implementation Guide

> 목적: Claude Code가 이 문서만 읽고도 MVP를 단계적으로 구현할 수 있도록 한다.
> 프로젝트 유형: 실제 시장 데이터를 활용하는 **모의투자 WTS (Web Trading System)**
> 디자인: **Figma에서 별도 설계**한다. Claude Code는 디자인을 임의로 과도하게 만들지 말고, 기능 중심의 React 컴포넌트와 명확한 UI 구조를 구현한다.
> UI/UX 참고 방향: **토스증권 WTS**, **KB 계열 증권 WTS 스타일**
> 주의: 특정 서비스의 UI를 그대로 복제하지 말고, 정보 구조와 사용자 흐름만 참고한다.
>
> **현재 단계: MVP(Phase 0~7) 완료 → Phase 8 실서비스 전환 (§56~§61).**
> Phase 8의 기준은 §56~§61이다. 앞 섹션과 충돌하면 §56~§61을 따른다.
>
> **이 서비스는 무조건 모의투자다 (§62).** 실제 주문·계좌·자금은 없고, 체결은 서비스 안에서 시뮬레이션한다.
> §62는 모든 섹션보다 우선한다. Phase 9 이후 전체 로드맵은 §63이다.
> Cognito는 나중에(공개 배포 직전, Phase 11) 붙인다. 그 전에는 배포하지 않는다.
>
> **섹션 번호를 바꾸지 않는다.** 코드 주석과 문서가 `§번호`로 이 문서를 700곳 넘게 참조한다.
> 섹션을 중간에 끼워 넣거나 번호를 당기지 말고, 새 내용은 끝에 새 번호로 덧붙인다.

---

# 0. 최우선 지침

이 프로젝트는 3인 팀의 MVP 프로젝트다.

아래 원칙을 반드시 지킨다.

1. **MVP가 먼저 동작하도록 구현한다.**
2. AWS/GCP 멀티클라우드, DR, 고가용성 Kafka 등은 MVP 완료 후 확장한다 (§63). Cognito는 공개 배포 직전에 붙인다 (§62.4).
3. 로컬 개발 환경에서 `docker compose up` 후 주요 기능이 실행되어야 한다.
4. 외부 한국투자증권 API 자격증명이 없어도 **Mock Market Mode**로 전체 서비스가 동작해야 한다.
5. 비즈니스 로직을 Controller, Repository, Kafka Consumer에 흩뿌리지 않는다.
6. Trading Core는 데이터 정합성이 가장 중요하다.
7. Market Service와 Trading Service는 강하게 결합하지 않는다.
8. Market Service가 한국투자증권 OpenAPI와 직접 통신한다.
9. Spring Cloud Gateway는 **클라이언트의 Ingress** 용도다. KIS OpenAPI 호출을 중계하지 않는다.
10. MySQL은 거래 데이터의 Source of Truth이다.
11. Valkey는 최신 시세/호가 등 재생성 가능한 실시간 캐시 용도다.
12. Kafka는 이벤트 스트림 용도다.
13. 구현이 복잡해질 경우 “실서비스 수준 기능”보다 “정확하게 동작하는 MVP”를 우선한다.
14. 새로운 라이브러리를 추가하기 전에 기존 스택으로 해결할 수 있는지 확인한다.
15. 모든 주요 구현은 테스트 가능하게 작성한다.
16. **이 서비스는 무조건 모의투자다.** 실제 주문·계좌·자금을 만들지 않는다. 체결은 서비스 안의 시뮬레이션이고, KIS는 시세 전용(모의투자 vts 환경)으로만 쓴다 (§62).
17. Amazon Cognito는 나중에 붙인다. 그 전까지는 Mock Login을 쓰고, Cognito 없이 배포하지 않는다 (§62.4).

---

# 1. 프로젝트 한 줄 설명

**실제 한국 주식시장 시세를 기반으로 사용자가 가상 자금을 이용해 시장가/지정가 주문을 연습할 수 있는 실시간 모의투자 WTS**

> 실제 체결은 일어나지 않는다. 실제인 것은 시세뿐이다 (§62).

---

# 2. MVP 성공 기준

아래 시나리오가 처음부터 끝까지 동작하면 MVP가 완성된 것으로 본다.

```text
사용자 로그인(초기에는 Mock 가능)
        ↓
종목 검색
        ↓
실시간 현재가 조회
        ↓
차트 표시
        ↓
매수/매도 주문
        ↓
주문 검증
        ↓
모의 체결
        ↓
가상 예수금 / 보유수량 반영
        ↓
주문/체결 내역 조회
        ↓
포트폴리오/평가손익 확인
```

추가 성공 기준:

- 한국투자증권 API 연결이 없어도 Mock 시세로 위 전체 흐름이 동작한다.
- Kafka가 중단되었다가 복구되어도 주요 거래 DB 상태가 손상되지 않는다.
- 동일 주문 요청이 중복 생성되지 않는다.
- 사용자의 예수금은 음수가 될 수 없다.
  - Phase 14(미수 · 신용)에서 "미수 · 신용 한도 안에서만"으로 다시 정의한다 (§63).
- 보유한 수량보다 많이 매도할 수 없다.
- Market Service 장애가 Trading DB 정합성을 깨뜨리지 않는다.

---

# 3. MVP에서 제외할 범위

아래 기능은 1차 MVP에서 구현하지 않는다.

- 실제 주식 주문
- 실제 증권 계좌
- 실제 현금 입출금
- KRX 직접 연결
- 실제 청산/결제
- 신용거래 / 미수거래 / 대출
- 파생상품
- 공매도
- 완전한 호가 기반 Matching Engine
  - Phase 8의 체결 엔진은 시세를 보고 체결을 판정하는 모의 체결 엔진이다. 사용자 간 매칭은 여전히 범위 밖이다 (§56.4, §58)
- 복잡한 부분체결
- Event Sourcing
- Saga 기반 분산 트랜잭션
- Active-Active Multi-Cloud
- AWS → GCP 자동 Failover
- RPO 0 보장
- Cognito Multi-Region Replication
- 운영 수준 Kafka Multi-Cluster DR
- 모바일 앱
- Figma 없이 임의로 복잡한 UI 디자인

> **MVP 이후 →** 실제 주문 · 계좌 · 현금 입출금 · KRX 직접 연결 · 청산/결제는 영구 제외다 (§62.1).
> Cognito는 공개 배포 직전(Phase 11), Cognito MRR은 DR(Phase 12)에서 한다. 나머지도 §63 로드맵에 넣었다.
> Event Sourcing · Saga는 기능이 아니라 설계 선택이라 §46을 따른다. Active-Active · RPO 0을 하지 않는 이유는 §63.2.

---

# 4. 확정 기술 스택

## Frontend

```text
React
TypeScript
Vite
Zustand
TanStack Query
TradingView Lightweight Charts
WebSocket
Axios 또는 fetch
```

UI 스타일은 Figma 산출물에 맞춘다.

초기에는 최소한의 CSS 또는 디자인 토큰 placeholder만 사용한다.

## Backend

```text
Java 21
Spring Boot 3.x
Spring Cloud
Spring Cloud Gateway
Spring Security
Spring Data JPA
Spring Validation
Spring Kafka
Spring Data Redis
WebSocket
Resilience4j
Micrometer
Gradle
```

## Data / Messaging

```text
MySQL 8.x
Valkey
Apache Kafka
```

## Local Infra

```text
Docker
Docker Compose
```

## CI/CD

MVP 단계:

```text
Self-Managed GitLab
GitLab CI
GitLab Runner
```

MVP 이후:

```text
Argo CD
Kubernetes
AWS EKS
GCP GKE
Terraform
```

## Observability – MVP 이후 또는 MVP 후반

```text
OpenTelemetry
Prometheus
Grafana
Loki
Tempo
```

---

# 5. 전체 논리 아키텍처

```text
                       ┌─────────────────────┐
                       │     React WTS       │
                       └──────────┬──────────┘
                                  │
                           REST / WebSocket
                                  │
                                  ▼
                      Spring Cloud Gateway
                                  │
             ┌────────────────────┼────────────────────┐
             │                    │                    │
             ▼                    ▼                    ▼
      Market Service       Trading Service       User Service
             │                    │                    │
             │                    │                    │
             │                    ▼                    ▼
             │                  MySQL                MySQL
             │
             ├─────── Kafka ────────────────┐
             │                              │
             ▼                              ▼
           Valkey                    Trading Consumers
             │
             ▼
      Latest Market State


한국투자증권 OpenAPI
          │
          │ REST / WebSocket
          ▼
     Market Service

※ KIS OpenAPI는 Spring Cloud Gateway를 거치지 않는다.
```

---

# 6. 물리 서비스 구성

## 6.1 gateway-service

역할:

- API Routing
- 인증 필터
- CORS
- Rate Limiting은 구조만 마련
  - Phase 10에서 실제로 적용한다 (§63)
- 공통 Trace ID 생성
- REST 요청 라우팅
- WebSocket Route

경로 예:

```text
/api/market/**
→ market-service

/api/trading/**
→ trading-service

/api/users/**
→ user-service

/ws/market/**
→ market-service
```

## 6.2 market-service

역할:

- 한국투자증권 OpenAPI 연동
- 실시간 체결가 수신
- 실시간 호가 수신
- 다중 종목 Subscription 관리
- 최신 시세 Valkey 저장
- Kafka Market Event 발행
- 1분봉 생성
- WTS WebSocket 실시간 송신
- Mock Market Mode 제공
- (Phase 8) 종목 마스터 동기화 · 수요 기반 계층형 구독 (§57)

> **§66 →** 오토스케일링을 위해 두 앱으로 나뉘었다. `market-ingest`(KIS 연동 · 수집 · 마스터 · 1분봉 · 중계, 한 대)와
> `market-service`(REST · WebSocket 송신, 여러 대). 공유 코드는 `market-core` 모듈이다.

중요:

```text
Market Service ↔ Trading Service
```

직접 HTTP 호출을 기본 구조로 사용하지 않는다.

Trading Service가 시세가 필요하면:

1. Kafka `market.price.updated` 이벤트를 소비하거나
2. Valkey의 최신 가격을 조회한다.

## 6.3 trading-service

MVP 핵심 서비스다.

한 Spring Boot Application 내부에서 다음 모듈을 분리한다.

```text
trading-service
├── order
├── execution
├── account
├── position
├── portfolio
└── ledger
```

별도의 마이크로서비스로 쪼개지 않는다.

> **Phase 8 →** 체결 엔진(`engine/`)도 trading-service 안의 모듈로 둔다 (§58.1).

이유:

```text
주문 → 자금 예약 → 체결 → 잔고 변경 → 포지션 변경
```

이 흐름은 데이터 정합성이 강하게 요구되기 때문이다.

## 6.4 user-service

역할:

- 사용자 기본정보
- 프로필
- 관심종목
- 사용자 설정
- 인증 사용자 매핑

MVP 인증은 아래 중 하나를 선택한다.

우선순위:

```text
1. Mock Login
2. Spring Security 간단 인증
3. Amazon Cognito 연동
```

그 전까지는 1번 Mock Login을 쓴다. Cognito는 공개 배포 직전(Phase 11)에 붙인다 (§62.4).

---

# 7. 서비스 내부 아키텍처

각 Spring Boot 서비스는 가능한 한 아래 원칙을 따른다.

```text
Hexagonal Architecture
+
DDD-lite
+
Spring MVC
```

예시:

```text
order/

├── domain/
│   ├── Order.java
│   ├── OrderId.java
│   ├── OrderStatus.java
│   ├── OrderSide.java
│   ├── OrderType.java
│   └── OrderRepository.java
│
├── application/
│   ├── command/
│   ├── query/
│   └── service/
│
└── adapter/
    ├── in/
    │   ├── web/
    │   └── kafka/
    │
    └── out/
        ├── persistence/
        ├── kafka/
        └── cache/
```

---

# 8. 패키지 설계 규칙

금지:

```text
controller/
service/
repository/
entity/
```

만으로 애플리케이션 전체를 수평 분리하지 않는다.

대신 **도메인 기준**으로 먼저 분리한다.

좋은 예:

```text
com.team.wts.trading

├── order/
├── execution/
├── account/
├── position/
├── ledger/
└── common/
```

---

# 9. Domain Rules

## 9.1 초기 가상자금

신규 사용자에게:

```text
100,000,000 KRW
```

의 가상 자금을 지급한다.

환경변수 또는 설정값으로 변경 가능하게 한다.

```yaml
trading:
  initial-cash: 100000000
```

## 9.2 예수금

계좌는 최소 다음 값을 가진다.

```text
cash_balance
reserved_cash
```

주문 가능 금액:

```text
available_cash
=
cash_balance - reserved_cash
```

## 9.3 포지션

종목별:

```text
quantity
reserved_quantity
average_price
```

매도 가능 수량:

```text
available_quantity
=
quantity - reserved_quantity
```

## 9.4 매수 주문

매수 주문 시:

```text
available_cash >= required_amount
```

이어야 한다.

지정가 주문:

```text
required_amount = limit_price * quantity
```

시장가 주문은 MVP에서 최신 시세 기준으로 계산한다.

## 9.5 매도 주문

```text
available_quantity >= order_quantity
```

이어야 한다.

그렇지 않으면 Reject한다.

## 9.6 주문 상태

```text
RECEIVED
VALIDATED
ACCEPTED
FILLED
CANCELLED
REJECTED
```

MVP에서는 `PARTIALLY_FILLED`는 선택 기능이다.

> **Phase 8 →** 장 마감 만료 상태 `EXPIRED`를 추가한다 (§59.3).
> **Phase 9 →** `PARTIALLY_FILLED`를 넣는다. 호가 잔량 기반 부분체결이다 (§63).

## 9.7 주문 종류

```text
MARKET
LIMIT
```

## 9.8 매수 / 매도

```text
BUY
SELL
```

---

# 10. Execution Strategy

Strategy Pattern을 사용한다.

```java
public interface ExecutionStrategy {

    boolean canExecute(
        Order order,
        MarketPrice price
    );

    ExecutionResult execute(
        Order order,
        MarketPrice price
    );
}
```

구현체:

```text
MarketOrderExecutionStrategy
LimitOrderExecutionStrategy
```

---

# 11. 시장가 체결 규칙

MVP:

```text
현재 최신 가격 = 체결가격
```

시장가 매수/매도 요청은 최신 가격이 존재하면 즉시 체결한다.

최신 시세가 TTL을 초과하여 stale 상태이면 주문을 거절한다.

예:

```text
market.price.max-age = 5 sec
```

> **Phase 8 →** 시장가도 HTTP 요청 안에서 체결하지 않는다. 접수(`202` + `ACCEPTED`) 후 체결 엔진이 체결한다.
> 신선한 시세를 기다리는 시간 상한과 매수 예약금 규칙은 §58.3.

---

# 12. 지정가 체결 규칙

BUY:

```text
current_price <= limit_price
```

이면 체결 가능.

SELL:

```text
current_price >= limit_price
```

이면 체결 가능.

미체결 주문은 `ACCEPTED` 상태로 유지한다.

새로운:

```text
market.price.updated
```

이벤트 수신 시 미체결 주문을 검사한다.

> **Phase 8 →** 지정가는 호가단위의 배수이고 가격제한폭 안이어야 한다 (§59). 미체결 검사는 체결 엔진이 한다 (§58).

---

# 13. 락 / 동시성

거래 정합성을 위해 반드시 고려한다.

Account / Position 수정 시 Lock 순서를 통일한다.

```text
Account
→ Position
```

순서로 잠근다.

MVP에서는 다음 중 하나를 선택한다.

권장:

```text
Pessimistic Lock
```

또는 명확한 Version 관리가 가능하다면:

```text
Optimistic Lock
```

AI가 임의로 Redis Distributed Lock부터 도입하지 않는다.

---

# 14. Idempotency

주문 API는 중복 요청을 방지해야 한다.

Header 예:

```http
Idempotency-Key: UUID
```

동일 Key로 동일 사용자가 재요청하면:

- 주문을 새로 생성하지 않는다.
- 기존 주문 결과를 반환한다.

MySQL Unique Constraint 또는 별도 idempotency table 사용 가능.

---

# 15. Transactional Outbox

DB Transaction과 Kafka Publish 사이의 Dual Write 문제를 방지한다.

예:

```text
BEGIN

INSERT orders
UPDATE accounts
INSERT outbox_events

COMMIT
```

별도 Publisher가:

```text
outbox_events
↓
Kafka
```

로 전달한다.

MVP에서는 Polling Publisher를 사용해도 된다.

CDC/Debezium은 MVP 필수가 아니다.

---

# 16. Kafka Topic

최소 Topic:

```text
market.price.updated
order.created
order.accepted
order.cancelled
execution.completed
account.balance.changed
position.changed
ledger.created
audit.logged
```

> **Phase 8 →** `order.rejected`, `order.expired`, `order.open.snapshot`, `order.accepted.dlq`를 추가한다.
> `order.*` · `execution.completed`의 메시지 키를 종목코드로 바꾸고, 토픽은 코드로 명시해 만든다 (§58.4).

모든 Event에 아래 Metadata를 넣는다.

```json
{
  "eventId": "uuid",
  "eventType": "execution.completed",
  "occurredAt": "ISO-8601",
  "traceId": "uuid",
  "aggregateId": "string",
  "version": 1,
  "payload": {}
}
```

---

# 17. Kafka Consumer 규칙

Consumer는 반드시 중복 이벤트 가능성을 고려한다.

```text
At-Least-Once
```

를 기본 가정한다.

Consumer 처리 시:

```text
eventId
```

기준으로 중복처리를 방지한다.

실패 이벤트는 MVP 후반에 DLQ를 적용한다.

예:

```text
execution.completed.dlq
```

> **Phase 8 →** DLQ는 체결 엔진 입력(`order.accepted.dlq`)부터 적용한다.
> 중복 방지는 eventId 테이블이 아니라 주문 상태 머신이 한다 (ADR-0011, §58.5).

---

# 18. Valkey 사용 원칙

Valkey는 다음 용도:

```text
최신 현재가
최신 호가
현재 1분봉 상태
종목 기본 메타 캐시
WebSocket fan-out 보조
```

예시 Key:

```text
market:price:005930
market:orderbook:005930
market:candle:1m:005930
market:meta:005930
```

Valkey를 다음 용도로 사용하지 않는다.

```text
계좌 원본 데이터
주문 원본 데이터
원장 원본 데이터
체결 원본 데이터
```

위 데이터의 Source of Truth는 MySQL이다.

> **Phase 8 →** 종목 메타 · 시세 수요 · 구독 배정 키를 추가한다 (§60.2). 모두 다시 만들 수 있는 상태다.

---

# 19. KIS OpenAPI Adapter

도메인 계층에서 KIS 구현체를 직접 참조하지 않는다.

Port:

```java
public interface MarketDataProvider {

    MarketPrice getCurrentPrice(String symbol);

    void subscribe(String symbol);

    void unsubscribe(String symbol);
}
```

구현:

```text
KisMarketDataAdapter
MockMarketDataAdapter
```

Profile:

```text
spring.profiles.active=mock
```

또는:

```text
spring.profiles.active=kis
```

로 전환 가능하게 한다.

> **§62.3 →** KIS는 시세 전용이다. 주문 · 계좌 API를 부르지 않는다. 환경은 모의투자(vts)만 허용한다.

---

# 20. 한국투자증권 WebSocket

고려사항:

- Approval Key
- 실시간 체결 TR
- 실시간 호가 TR
- 다중 종목 구독
- reconnect
- heartbeat
- backoff
- subscription restore

구독 제한 숫자는 코드에 하드코딩하지 않는다.

설정값으로 둔다.

```yaml
kis:
  websocket:
    subscription-limit: 40
```

> **Phase 8 →** 등록 · 해제가 수요에 따라 바뀐다. 재연결하면 그 시점의 배정을 복구한다.
> REST 호출도 토큰 버킷 하나로 초당 한도를 지킨다 (`kis.rest.rate-limit-per-second`, §57.2).

---

# 21. Subscription Manager

최종 설계는 동적 구독 가능하도록 한다.

종목을 구독하는 조건:

```text
active_viewer_count > 0
OR
pending_order_count > 0
OR
system_required = true
```

MVP(Phase 0~7)는 고정 종목 목록(`market.stocks`)으로 시작했다.

> **Phase 8 →** 고정 목록을 없애고 수요 기반 계층형 구독(실시간 · 폴링 · 스냅샷)으로 바꾼다. 상세는 §57.2.
> 위의 세 조건은 우선순위가 붙은 수요 신호로 구체화된다:
> 시장가 대기 주문 > FOCUS 시청자 > 미체결 지정가 주문 > LIST 시청자 > 고정 종목.

---

# 22. 1분봉

Kafka의 체결 이벤트를 이용한다.

```text
09:00:00 ~ 09:00:59

Open
High
Low
Close
Volume
```

을 계산한다.

최종 1분봉은 DB에 저장한다.

테이블 예:

```text
candles_1m
```

MVP에서 체결 데이터가 부족하면 Mock Price Stream으로 캔들 생성 가능.

---

# 23. Database Schema Outline

> **Phase 8 →** market DB에 `stocks`(종목 마스터)가 생기고, `orders`에 인덱스와 `EXPIRED` 상태가 추가된다 (§60.1).

## users

```text
id
email
nickname
created_at
updated_at
```

## virtual_accounts

```text
id
user_id
cash_balance
reserved_cash
created_at
updated_at
version
```

## positions

```text
id
account_id
symbol
quantity
reserved_quantity
average_price
created_at
updated_at
version
```

Unique:

```text
(account_id, symbol)
```

## orders

```text
id
account_id
symbol
side
order_type
quantity
limit_price
filled_quantity
status
idempotency_key
created_at
updated_at
```

## executions

```text
id
order_id
symbol
side
price
quantity
executed_at
```

## ledger_entries

```text
id
account_id
order_id nullable
execution_id nullable
type
amount
before_balance
after_balance
created_at
```

Ledger Type 예:

```text
INITIAL_DEPOSIT
BUY
SELL
FEE
ADJUSTMENT
```

MVP 수수료는 `0`으로 둘 수 있다.

## watchlists

```text
id
user_id
symbol
created_at
```

## outbox_events

```text
id
aggregate_type
aggregate_id
event_type
payload_json
status
created_at
published_at
retry_count
```

## processed_events

```text
event_id
consumer_name
processed_at
```

Kafka Consumer idempotency용.

## candles_1m

```text
symbol
open_time
open
high
low
close
volume
```

Primary Key:

```text
(symbol, open_time)
```

---

# 24. REST API

> **Phase 8 →** 종목 목록 페이지화, 배치 시세 조회 신설, 주문 접수 `202` (§60.4).

## User

```http
POST /api/users/mock-login
GET /api/users/me
GET /api/users/me/watchlist
POST /api/users/me/watchlist/{symbol}
DELETE /api/users/me/watchlist/{symbol}
```

## Market

```http
GET /api/market/stocks
GET /api/market/stocks/{symbol}
GET /api/market/stocks/{symbol}/price
GET /api/market/stocks/{symbol}/orderbook
GET /api/market/stocks/{symbol}/candles?interval=1m
```

## Trading

```http
POST /api/trading/orders
GET /api/trading/orders
GET /api/trading/orders/{orderId}
DELETE /api/trading/orders/{orderId}
GET /api/trading/account
GET /api/trading/positions
GET /api/trading/portfolio
GET /api/trading/executions
```

주문 Request:

```json
{
  "symbol": "005930",
  "side": "BUY",
  "orderType": "LIMIT",
  "quantity": 10,
  "limitPrice": 80000
}
```

Header:

```http
Idempotency-Key: UUID
```

---

# 25. WebSocket Contract

Endpoint 예:

```text
/ws/market
```

Client Subscribe:

```json
{
  "type": "SUBSCRIBE",
  "symbols": ["005930", "000660"]
}
```

Unsubscribe:

```json
{
  "type": "UNSUBSCRIBE",
  "symbols": ["005930"]
}
```

Server Price Update:

```json
{
  "type": "PRICE",
  "symbol": "005930",
  "price": 80000,
  "change": 1000,
  "changeRate": 1.27,
  "volume": 123456,
  "timestamp": "2026-09-21T10:30:00+09:00"
}
```

Server Orderbook:

```json
{
  "type": "ORDERBOOK",
  "symbol": "005930",
  "asks": [],
  "bids": [],
  "timestamp": "..."
}
```

> **Phase 8 →** `SUBSCRIBE`에 `mode: FOCUS | LIST`를 더한다 (생략하면 LIST). 세션당 구독 종목 수에 상한이 있다 (§57.3).

---

# 26. Frontend Pages

Figma가 디자인한다.

Claude Code는 기능 기준으로 아래 Route와 Component 구조를 먼저 만든다.

```text
/login
/wts
/wts/:symbol
/orders
/portfolio
```

---

# 27. WTS 화면 필수 영역

대표 화면:

```text
┌───────────────────────────────────────┐
│ Search / User / Account Summary       │
├───────────────┬───────────────────────┤
│ Watchlist     │ Stock Header          │
│               │ Current Price         │
│               ├───────────────────────┤
│               │ Chart                 │
│               ├───────────┬───────────┤
│               │ Orderbook │ Order     │
│               │           │ Panel     │
├───────────────┴───────────┴───────────┤
│ Position / Open Orders / Executions   │
└───────────────────────────────────────┘
```

Figma 산출물이 나오기 전에는 이 구조를 유지하는 최소 UI만 구현한다.

---

# 28. Frontend Component 후보

```text
StockSearch
Watchlist
StockHeader
RealtimePrice
StockChart
OrderBook
OrderForm
OpenOrders
ExecutionHistory
PortfolioSummary
PositionTable
AccountSummary
ConnectionStatus
```

---

# 29. Frontend State

Zustand:

```text
userStore
marketStore
tradingStore
websocketStore
```

TanStack Query:

```text
종목 검색
계좌 조회
포트폴리오 조회
주문 조회
체결 조회
```

WebSocket:

```text
실시간 현재가
호가
체결 알림
```

> **Phase 8 →** WebSocket은 화면에 보이는 종목만 구독한다.
> 체결 알림은 사용자 채널 없이 조회(`useFillWatcher`)로 확인한다 (§57.3). 사용자별 체결 푸시는 Phase 9 (§63).

---

# 30. 디자인 구현 원칙

Figma가 최종 디자인의 Source of Truth다.

Claude Code는:

- 임의의 화려한 색상 사용 금지
- 임의의 애니메이션 남발 금지
- 특정 증권사 UI 복제 금지
- 컴포넌트 재사용 가능하게 구현
- Design Token 적용 가능 구조 마련

예:

```css
:root {
  --color-primary: #16a34a;
  --color-background: #ffffff;
  --color-surface: #f8fafc;
  --color-text: #0f172a;
}
```

위 값은 Placeholder이며 Figma에 맞춰 교체 가능하게 한다.

---

# 31. Mock Market Mode

KIS API 없이 반드시 실행되어야 한다.

Mock Mode는:

```text
005930
000660
035420
035720
005380
```

등의 종목에 대해 가격을 생성한다.

예:

```text
삼성전자 80,000
± 0.1 ~ 0.5%
```

정도의 Random Walk를 사용할 수 있다.

하지만 테스트는 Random에 의존하지 않게 Seed 또는 Fixed Sequence 사용 가능하도록 한다.

> **Phase 8 →** 고정 5종목이 아니라 종목 마스터 전체가 대상이다. 수요가 있는 종목만 가격을 만들고, 출발 가격은 마스터 기준가다.
> 마스터는 저장소의 스냅샷 CSV로 채운다. 네트워크 없이 뜬다 (§57.1, §57.2).
>
> **§67 →** random walk는 결정형 모의 시세로 바뀌었다. 같은 시각이면 언제 계산해도 같은 값이다. kis 모드에서도 실시간으로 받지 않는 종목에 같은 모델을 쓴다.

---

# 32. Local Docker Compose

최소 구성:

```text
MySQL
Kafka
Valkey
Kafka UI optional
```

예상:

```yaml
services:
  mysql:
  kafka:
  valkey:
  kafka-ui:
```

백엔드와 Frontend는 개발 중 로컬 프로세스로 실행해도 된다.

후반에는 Docker Compose 전체 실행도 지원한다.

---

# 33. 환경변수

예:

```env
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_DATABASE=wts
MYSQL_USER=wts
MYSQL_PASSWORD=wts

KAFKA_BOOTSTRAP_SERVERS=localhost:9092

VALKEY_HOST=localhost
VALKEY_PORT=6379

KIS_APP_KEY=
KIS_APP_SECRET=
KIS_ACCOUNT_NO=

MARKET_PROVIDER=mock
```

Secret은 Git에 Commit하지 않는다.

> **§62.3 →** `KIS_ACCOUNT_NO`는 필요 없다 (KIS 주문 API를 쓰지 않는다). `KIS_ENVIRONMENT`는 `vts`만 허용한다.

---

# 34. Repository Structure

Monorepo 권장:

```text
wts-platform/

├── frontend/
│
├── backend/
│   ├── gateway-service/
│   ├── market-service/
│   ├── trading-service/
│   └── user-service/
│
├── infra/
│   ├── docker/
│   └── scripts/
│
├── docs/
│   ├── architecture/
│   ├── api/
│   └── decisions/
│
├── .gitlab-ci.yml
├── docker-compose.yml
└── CLAUDE.md
```

---

# 35. Testing

Backend 필수:

```text
JUnit 5
Mockito
Spring Boot Test
Testcontainers
```

특히 아래는 반드시 테스트한다.

```text
잔고 부족 매수 거절
보유수량 초과 매도 거절
동일 Idempotency Key 중복 주문 방지
시장가 체결
지정가 미체결
지정가 가격조건 충족 시 체결
체결 후 Cash Balance 변경
체결 후 Position 변경
평균단가 계산
매도 후 실현손익 계산
이미 체결된 주문 취소 거절
```

---

# 36. Integration Test

Testcontainers:

```text
MySQL
Kafka
Valkey
```

를 사용한다.

테스트 시나리오:

```text
market.price.updated 발생
→ 지정가 주문 체결
→ execution 생성
→ account 변경
→ position 변경
→ ledger 생성
```

전체 흐름을 검증한다.

---

# 37. CI – GitLab

MVP CI Pipeline:

```text
Push / Merge Request
      ↓
Frontend Lint
Frontend Build
      ↓
Backend Compile
Backend Unit Test
      ↓
Integration Test
      ↓
Docker Build
```

아직 Kubernetes Deploy는 필수가 아니다.

---

# 38. GitLab CI Stage 예

```yaml
stages:
  - lint
  - test
  - build
  - integration
  - docker
```

MVP 완료 후:

```text
SonarQube
Image Registry Push
GitOps Repository Update
Argo CD
```

를 추가한다.

> **→ Phase 10~11 (§63).**

---

# 39. Error Response

공통 형식:

```json
{
  "code": "INSUFFICIENT_BALANCE",
  "message": "주문 가능한 예수금이 부족합니다.",
  "traceId": "uuid",
  "timestamp": "..."
}
```

Domain Error Code:

```text
INSUFFICIENT_BALANCE
INSUFFICIENT_POSITION
INVALID_ORDER_STATE
MARKET_PRICE_UNAVAILABLE
MARKET_PRICE_STALE
ORDER_NOT_FOUND
DUPLICATE_ORDER_REQUEST
```

> **Phase 8 →** `INVALID_TICK_SIZE`, `PRICE_LIMIT_EXCEEDED`, `MARKET_CLOSED`, `SYMBOL_NOT_TRADABLE`을 추가한다 (§60.3).

---

# 40. Logging

모든 요청에:

```text
traceId
```

를 생성한다.

주요 거래 이벤트에는:

```text
traceId
userId
accountId
orderId
symbol
```

을 구조화 로그로 남긴다.

비밀번호, Secret, Token 전체값은 로그에 기록하지 않는다.

---

# 41. Audit Event

MVP 후반 적용:

```text
LOGIN_SUCCESS
ORDER_CREATED
ORDER_CANCELLED
EXECUTION_COMPLETED
BALANCE_CHANGED
POSITION_CHANGED
```

Kafka:

```text
audit.logged
```

로 발행 가능하게 만든다.

> **→ 아직 구현되지 않았다. Phase 9에서 넣는다 (§63).**

---

# 42. 금액 타입

금융 데이터에 `double`, `float` 사용 금지.

Java:

```text
BigDecimal
```

사용.

DB:

```text
DECIMAL
```

사용.

수량:

```text
BIGINT
```

또는 필요에 맞는 정수 타입.

---

# 43. 시간

Backend 저장:

```text
UTC
```

권장.

Frontend 표현:

```text
Asia/Seoul
```

한국 장 운영시간과 시세 Timestamp 변환을 명확히 한다.

---

# 44. 수수료

MVP:

```text
fee = 0
tax = 0
```

가능.

단, 향후 변경 가능하도록 Calculator Interface로 분리.

```java
TradingFeeCalculator
```

---

# 45. CQRS-lite

완전한 CQRS를 구현하지 않는다.

패키지와 Use Case 수준에서:

```text
Command
Query
```

를 분리한다.

예:

```text
command/
    PlaceOrderCommand
    CancelOrderCommand

query/
    GetOrdersQuery
    GetPortfolioQuery
```

별도 Read DB는 만들지 않는다.

---

# 46. 사용하지 않을 패턴

1차 MVP에서는 다음을 추가하지 않는다.

```text
Event Sourcing
Full CQRS
Saga
2PC
Distributed Transaction
Service Mesh
Multi-region Kafka
Complex Matching Engine
```

> **Phase 9 이후에도 유지한다.** 두 항목은 뜻을 좁힌다.
> - Complex Matching Engine = 사용자 간 주문 매칭. 실제 호가 잔량으로 체결을 시뮬레이션하는 것(Phase 9)은 여기에 해당하지 않는다.
> - Multi-region Kafka = 양방향 · 액티브 멀티리전. DR용 단방향 미러링(Phase 12)은 허용한다.

---

# 47. 구현 순서

Claude Code는 한 번에 전체 프로젝트를 생성하지 말고 아래 순서로 구현한다.

## Phase 0 – Bootstrap

- Monorepo 생성
- React + Vite 생성
- Spring Boot 서비스 생성
- Docker Compose
- MySQL
- Kafka
- Valkey
- Health Check

완료조건:

```text
모든 서비스가 실행되고 health endpoint가 200
```

## Phase 1 – User / Account Skeleton

- Mock Login
- User
- Virtual Account
- 1억원 초기 지급
- Account API

완료조건:

```text
GET /api/trading/account
```

에서 1억원 확인.

## Phase 2 – Mock Market

- MockMarketDataAdapter
- Kafka price event
- Valkey latest price
- Market REST API
- WebSocket
- Frontend 가격 표시

완료조건:

WTS 화면에서 가격이 실시간 변경.

## Phase 3 – Trading

- Order
- Account
- Position
- Market Order
- Limit Order
- Reserved Cash
- Reserved Quantity
- State Machine
- Idempotency

완료조건:

시장가 매수 후:

```text
cash 감소
position 증가
```

확인.

## Phase 4 – Execution + Ledger

- Execution
- Ledger
- Outbox
- Kafka Events
- Pending Limit Order

완료조건:

```text
지정가 주문
→ 가격조건 도달
→ 자동 체결
→ Ledger 생성
```

## Phase 5 – WTS MVP

- 종목 검색
- Watchlist
- 실시간 가격
- 차트
- 주문 Form
- 포지션
- 미체결 주문
- 체결내역
- 포트폴리오

Figma 디자인이 있으면 디자인 적용.

## Phase 6 – KIS Integration

- Approval Key
- WebSocket Connect
- Reconnect
- Subscribe
- Unsubscribe
- TR Parsing
- Adapter Switch

완료조건:

```text
MARKET_PROVIDER=kis
```

로 실제 데이터 수신.

## Phase 7 – CI

- GitLab CI
- Frontend Build
- Backend Test
- Integration Test
- Docker Build

## Phase 8 – 실서비스 전환

전 종목 · 계층형 구독 · Kafka 체결 엔진 · 거래 규칙. 네 단계(8-1 ~ 8-4)로 나눠 진행한다.
범위 · 설계 · 순서 · 완료조건은 §56~§61.

## Phase 9 ~ 16

§63 로드맵을 따른다. 각 Phase를 시작하기 전에 상세 설계를 이 문서 끝에 덧붙인다.

---

# 48. Phase 9 이후 확장

> 아래 항목은 §63 로드맵의 Phase 10~12에 배치했다. Amazon Cognito는 Phase 11의 첫 작업이다 (§62.4).
>
> 코드 주석 · 문서 9곳(`MockAuthToken`, `MockLoginService`, `LoginPanel`, `userStore`, `.env.example`,
> ADR-0006, phase1 · phase3 아키텍처 문서)에 남은 "Phase 8에서 Cognito로 교체"는 이제 번호가 틀렸다.
> Phase 8-1 작업 때 "Phase 11(공개 배포 직전)에서 Cognito로 교체"로 고친다.

MVP 이후 별도 작업:

```text
Amazon Cognito          → Phase 11, 공개 배포 직전 (§62.4)
AWS EKS
AWS RDS MySQL
AWS ElastiCache/Valkey
AWS MSK
AWS ECR
GCP GKE
GCP Cloud SQL
GCP Memorystore
Terraform
Argo CD
AWS → GCP DR
DB Replication
RPO / RTO 측정
OpenTelemetry
Prometheus
Grafana
Loki
Tempo
```

---

# 49. Definition of Done

MVP 최종 데모:

1. 사용자가 로그인한다.
2. 가상 자금 1억원을 확인한다.
3. 삼성전자를 검색한다.
4. 실시간 가격과 차트를 확인한다.
5. 시장가 매수 주문을 한다.
6. 즉시 체결된다.
7. 예수금이 감소한다.
8. 보유 삼성전자 수량이 증가한다.
9. 평가손익이 실시간 가격에 따라 변경된다.
10. 지정가 주문을 등록한다.
11. 가격이 조건에 도달하면 자동 체결된다.
12. 주문/체결 내역을 확인한다.
13. Ledger에서 자산변경을 확인할 수 있다.
14. 동일 Idempotency Key 요청은 중복 주문되지 않는다.
15. Mock/KIS Market Provider를 설정만으로 변경할 수 있다.

> **Phase 8 →** 6번 "즉시 체결된다"는 "접수 후 수 초 안에 체결된다"로 바뀐다 (§58). Phase 8의 완료 기준은 §56.5.

---

# 50. Claude Code 작업 규칙

Claude Code는 작업 시작 전:

1. 현재 Repository 구조를 확인한다.
2. 이미 구현된 기능을 확인한다.
3. 이 문서와 충돌하는 기존 코드가 있으면 먼저 보고한다.
4. 작업 계획을 짧게 제시한다.
5. 변경 파일 목록을 제시한다.
6. 구현한다.
7. 테스트한다.
8. 테스트 결과를 보고한다.

추가 규칙:

- 이 문서의 섹션 번호는 바꾸지 않는다. 새 내용은 끝에 새 번호로 덧붙인다 (코드가 `§번호`로 참조한다).
- 설계 결정을 바꾸면 `docs/decisions/`에 ADR을 남긴다. 기존 ADR을 대체하면 양쪽에 서로를 적는다.
- API 계약을 바꾸면 명세를 다시 생성한다 (`./infra/scripts/generate-openapi.sh`, §60.4).

---

# 51. 한 번에 수정할 범위

좋은 요청:

```text
Phase 2의 Mock Market 기능을 구현해.
```

좋은 요청:

```text
Order Domain과 State Machine만 구현하고 테스트해.
```

나쁜 요청:

```text
전체 증권 시스템을 한 번에 만들어.
```

---

# 52. AI 코드 품질 규칙

자동 생성 코드라도 다음 기준을 지킨다.

- 의미 없는 추상화 금지
- 과도한 Interface 생성 금지
- 사용하지 않는 코드 생성 금지
- TODO를 숨기지 말 것
- Exception을 무시하지 말 것
- 빈 catch 금지
- 금융 계산에 floating point 금지
- 모든 Transaction Boundary를 명확히 할 것
- 테스트 없이 핵심 거래 로직을 완료 처리하지 말 것
- 환경변수에 없는 Secret을 코드에 넣지 말 것

---

# 53. 참고 UI 방향

UI/UX 참고:

```text
토스증권
KB 계열 증권 WTS
```

참고할 것:

- 빠른 종목 탐색
- 현재가 중심 정보 구조
- 차트와 주문 영역의 명확한 분리
- 보유자산과 손익의 높은 가시성
- 초보 사용자도 주문을 이해하기 쉬운 흐름

복제하지 말 것:

- 브랜드 로고
- 고유 색상 체계
- 고유 아이콘
- 고유 레이아웃의 픽셀 단위 복제
- 고유 문구

최종 시각 디자인은 Figma 결과를 따른다.

---

# 54. 최종 목표 아키텍처와 MVP의 관계

MVP:

```text
Local Docker
+
React
+
Spring Boot
+
Kafka
+
Valkey
+
MySQL
```

최종:

```text
React WTS
      ↓
Spring Cloud Gateway
      ↓
Spring Boot Services
      ↓
Kafka / Valkey / MySQL
      ↓
AWS EKS Primary
      ↓
GCP GKE DR
```

MVP에서 작성하는 코드는 향후 Kubernetes와 Multi-Cloud로 이전할 수 있도록:

- Stateless Application
- Externalized Configuration
- Container Ready
- Health Check
- Graceful Shutdown
- Structured Logging

을 적용한다.

---

# 55. 프로젝트 핵심 메시지

이 프로젝트의 핵심은 단순 CRUD WTS가 아니다.

```text
Real-Time Market Data
+
Consistent Trading Domain
+
Event-Driven Architecture
+
Cloud-Native Deployment
+
Multi-Cloud Disaster Recovery
```

를 단계적으로 구현하는 것이 목표다.

그러나 **MVP의 최우선 목표는 실제로 거래 흐름이 끝까지 동작하는 것**이다.

Cloud / DR / Observability는 그 이후 확장한다.

---

# 56. Phase 8 – 실서비스 전환 개요

> MVP(Phase 0~7)는 끝났다. Phase 8은 **설정 파일의 5종목을 실제 상장 전 종목으로 넓히고,
> 체결을 Kafka 기반 엔진으로 옮기는 단계**다.
>
> §56~§61이 Phase 8의 기준이다. 앞 섹션과 충돌하면 §56~§61을 따른다.
> 앞 섹션에는 바뀌는 곳마다 `Phase 8 →` 메모를 달아 두었다.

## 56.1 목표

```text
1. 종목  설정 파일 5종목            → KOSPI · KOSDAQ 상장 주권 전 종목 (약 2,500개)
2. 시세  기동 시 고정 구독          → 수요 기반 계층형 구독 (실시간 · 폴링 · 스냅샷)
3. 체결  HTTP 요청 안에서 즉시 체결 → 접수와 체결 분리, Kafka 체결 엔진
4. 규칙  지정가 0원 초과만 검사     → 호가단위 · 가격제한폭 · 장 운영시간 · 거래정지
```

## 56.2 왜 "전 종목 실시간 구독"이 아닌가

KIS 실시간 WebSocket은 세션 하나당 등록 건수에 상한이 있다. 종목 하나에 체결 1건 + 호가 1건이 등록되므로
실시간으로 받을 수 있는 종목은 20개 안팎이다. REST 시세 조회도 초당 건수 제한이 있다.
앱키를 여러 개 써서 상한을 우회하지 않는다.

그러므로 전 종목을 항상 실시간으로 두는 것은 불가능하고, 필요하지도 않다.
사용자가 지금 보고 있거나 체결을 기다리는 종목만 신선하면 된다.

```text
전 종목     검색 · 조회 가능 (종목 마스터)
보는 종목   실시간 또는 준실시간 시세
주문 종목   체결 판정에 필요한 만큼 신선한 시세
나머지      누가 조회할 때 스냅샷
```

상세 설계는 §57.

## 56.3 현재 구현과 달라지는 점 (§50-3 보고)

| 영역 | 현재 (Phase 7) | Phase 8 |
|---|---|---|
| 종목 마스터 | `application.yml`의 `market.stocks` 5종목 (`ConfiguredStockRepository`) | market DB `stocks` 테이블. KIS 종목정보 파일로 동기화 |
| KIS 구독 | 기동 시 고정 목록 전부 등록 (`KisMarketDataAdapter.start`) | Subscription Manager가 수요에 따라 등록·해제 |
| Mock 시세 | 설정 종목 전부 1초 틱 | 수요가 있는 종목만 틱. 출발 가격은 마스터 기준가 |
| 프론트 구독 | 전체 종목을 받아 전부 WebSocket 구독 (`WtsPage`) | 화면에 보이는 종목만 구독. 목록은 검색 + 페이지 |
| 시장가 주문 | HTTP 트랜잭션 안에서 즉시 체결, `201` + `FILLED` | 접수만 하고 `202` + `ACCEPTED`. 엔진이 체결 |
| 지정가 자동 체결 | `MarketPriceUpdatedConsumer` → `PendingOrderExecutionService` | 이 경로를 체결 엔진으로 확장한다 (재사용) |
| Kafka 토픽 | 브로커 자동 생성 (`KAFKA_NUM_PARTITIONS=3`). 주문 이벤트 키 = orderId | 코드로 명시 생성. 엔진 입력 토픽 키 = 종목코드 |
| 호가단위 | `KrxTickSize`가 market-service에만 있고 Mock 가격에만 쓰인다. 주석과 달리 지정가 검증에는 안 쓰인다 | common으로 옮겨 지정가 검증에 쓴다 (§59) |
| 미체결 조회 | `orders(symbol, status, order_type)` 인덱스 없음 | 인덱스 추가. 엔진이 틱마다 조회한다 |
| 없는 종목 주문 | `MARKET_PRICE_UNAVAILABLE`로 뭉뚱그린다 | `SYMBOL_NOT_FOUND`로 구분한다 |
| 종목코드 형식 | 숫자 6자리(`\d{6}`)만 받는다. 영문이 섞인 85개 종목은 주문 · 관심종목이 막힌다 | `[0-9A-Z]{6}` (8-1에서 반영) |

유지하는 결정:

```text
ADR-0008  trading은 최신 시세를 Valkey에서 읽는다
ADR-0009  잔고·포지션을 바꾸는 모든 경로는 계좌 행 비관적 락으로 시작한다 (취소 API도 이미 그렇다)
ADR-0010  Kafka 발행은 Outbox를 거친다
ADR-0011  중복 처리는 processed_events가 아니라 주문 상태 머신이 막는다
```

대체되는 결정: §21의 "MVP는 고정 종목 목록", ADR-0012 중 기동 시 고정 구독 부분.
구현할 때 ADR을 새로 남긴다 (§61.3).

## 56.4 Phase 8 범위 밖

Phase 8에서는 하지 않고 이후 단계에서 한다 (§63):

```text
호가 잔량 기반 부분체결 · 슬리피지     Phase 9
동시호가 · 시간외 거래                 Phase 9
사용자별 체결 푸시 채널                Phase 9    Phase 8에서는 지금처럼 조회로 확인한다
market-service 다중 인스턴스           Phase 10   KIS 연결은 앱키당 하나다
ETF · ETN · ELW · 리츠 · 코넥스        Phase 13   호가단위 · 규칙이 다르다
```

하지 않는다 (§63.2): 사용자 간 주문 매칭, KIS 앱키 여러 개로 구독 상한 우회.

## 56.5 성공 기준

```text
- 상장 주권 전 종목을 종목코드·이름 부분일치로 검색할 수 있다.
- 어떤 종목이든 상세 화면을 열면 수 초 안에 시세가 보이고 갱신된다.
- 실시간 슬롯이 꽉 차도 화면을 연 종목은 폴링으로 갱신된다.
- 어떤 종목이든 시장가 주문은 체결되거나 정해진 이유로 거절된다. ACCEPTED로 계속 남지 않는다.
- 실시간 구독하지 않는 종목의 지정가 주문도 가격 조건에 도달하면 체결된다 (폴링 주기만큼 늦을 수 있다).
- Kafka가 멈춰도 주문 접수는 된다. Kafka가 돌아오면 쌓인 주문이 체결된다.
- 같은 주문은 두 번 체결되지 않는다. 엔진 인스턴스가 여러 개여도 마찬가지다.
- 체결 순서가 어떻게 되든 예수금은 음수가 되지 않는다.
- KIS 자격증명 없이 Mock 모드로 위 전부가 동작한다 (§0-4). 네트워크 없이도 뜬다.
```

---

# 57. 전 종목 시세 – 종목 마스터와 계층형 구독

## 57.1 종목 마스터

출처는 한국투자증권이 공개하는 종목정보 파일이다.

```text
kospi_code.mst.zip, kosdaq_code.mst.zip
- 인증 없이 내려받는다. URL은 설정값으로 둔다.
- cp949 인코딩, 줄마다 고정폭 레코드.
- 필드 위치는 한국투자증권 open-trading-api 저장소의 종목정보 예제(stocks_info)로 확인한다.
  추측해서 자르지 않는다. 실제 파일 일부를 테스트 픽스처로 두고 파서를 고정한다.
```

1차 범위는 KOSPI · KOSDAQ의 **주권**(보통주 · 우선주)이다. 증권그룹이 주권이 아닌 레코드는 건너뛴다.
주권 외 상품은 Phase 13에서 넣는다 (§63).

market DB의 `stocks` 테이블이 market-service 안에서 종목 마스터의 원본이다.

```text
stocks
symbol          VARCHAR(20)   PK, 단축코드. 영문 대문자가 섞일 수 있다 (0001A0, 00088K)
standard_code   VARCHAR(12)   표준코드 (ISIN)
name            VARCHAR       한글 종목명
market          VARCHAR       KOSPI | KOSDAQ
base_price      DECIMAL       기준가. 가격제한폭과 Mock 출발 가격의 기준. 파일에 0이면 NULL
market_cap      BIGINT        전일 기준 시가총액(억원). 검색 정렬용
trading_halted  BOOLEAN       거래정지
listed          BOOLEAN       파일에서 사라지면 false. 행은 지우지 않는다 (주문·포지션이 참조한다)
synced_at       TIMESTAMP     UTC
```

동기화:

```text
- 매 영업일 장 시작 전 (설정 cron, 예: 08:00 KST). 기동 시 테이블이 비어 있으면 한 번
- upsert. 파일에서 사라진 종목은 listed=false
- 다운로드나 파싱이 실패하면 기존 데이터를 그대로 두고 경고를 남긴다. 테이블을 비우지 않는다
- 동기화 후 종목 메타를 Valkey market:meta:{symbol} 에 쓴다 (§59.2에서 trading이 읽는다)
```

오프라인 · Mock · 테스트:

```text
- KIS 원본 zip을 그대로 스냅샷으로 저장소에 둔다 (market-service resources/master/, ADR-0014).
  다운로드와 같은 파서를 탄다. 갱신은 ./infra/scripts/update-stock-master-snapshot.sh
- market.provider=mock 이거나 내려받을 수 없으면 스냅샷으로 채운다.
- 테스트는 스냅샷 일부만 담은 고정 픽스처를 쓴다. 네트워크에 의존하지 않는다.
```

검색:

```text
GET /api/market/stocks?keyword=&market=&page=0&size=20
- keyword  종목코드 또는 이름 부분일치. 2,500행이라 DB LIKE로 충분하다. 검색 엔진을 들이지 않는다
- size     최대 100
- 응답     페이지 객체 { items, page, size, totalElements }
- listed=false 종목은 기본 제외
```

`StockRepository` 포트는 그대로 두고 구현만 설정 → DB로 바꾼다. 설정의 `market.stocks`는 지운다.

파일 레이아웃 · 오프셋 · 영문 종목코드 등 구현하며 확인한 사실은 ADR-0014에 있다.
종목코드 형식은 `common`의 `StockSymbol`(`[0-9A-Z]{6}`)이 서비스 공통 규칙이다.

## 57.2 계층형 구독 – Subscription Manager (§21 대체)

종목마다 수요를 모아 시세 계층을 정한다.

```text
수요 신호              출처
FOCUS 시청자          상세 화면을 연 세션       market WebSocket SUBSCRIBE (mode=FOCUS)
LIST 시청자           목록·관심종목에 보이는 행  market WebSocket SUBSCRIBE (mode=LIST)
시장가 대기 주문      체결을 기다리는 시장가    trading 이벤트 order.accepted (orderType=MARKET)
미체결 지정가 주문    ACCEPTED 지정가           trading 이벤트 order.accepted / 종결 이벤트
고정 종목             설정                      market.pinned-symbols
```

계층:

| 계층 | 수단 | 대상 | 신선도 |
|---|---|---|---|
| 실시간 | KIS WebSocket | 우선순위 상위 종목 (슬롯 한도까지) | 체결 틱마다 |
| 폴링 | KIS REST, 유량 한도 안에서 순환 | 수요는 있는데 실시간에 못 든 종목 | 폴링 대상 수 ÷ 초당 한도 |
| 스냅샷 | KIS REST 1회 | 그 외 종목을 누가 조회했을 때 | 조회 시점 |

우선순위 (높은 것부터):

```text
1. 시장가 대기 주문이 있는 종목   즉시 스냅샷 1회 + 폴링 최우선 (§58.3)
2. FOCUS 시청자가 있는 종목
3. 미체결 지정가 주문이 있는 종목
4. LIST 시청자가 있는 종목
5. 고정 종목
```

실시간 슬롯 배정:

```text
- 슬롯 = kis.websocket.subscription-limit (등록 건수). 정확한 상한은 KIS 공지로 확인하고 설정에 둔다.
- 체결 TR과 호가 TR을 따로 배정한다. 호가 TR은 FOCUS 종목에만 준다.
  나머지는 체결 TR만 받는다 → 같은 한도로 더 많은 종목을 실시간으로 둔다.
- 슬롯을 뺏을 때는 최소 유지 시간(설정, 예: 60초)을 지킨다. 화면 전환마다 등록·해제가 요동치지 않게 한다.
- 변경은 모아서 일정 주기(설정)로 반영한다. 재연결 시에는 현재 배정을 그대로 복구한다 (§20 subscription restore).
```

폴링:

```text
- KIS REST 호출 전체(초기 시세, 폴링, 스냅샷, 호가)가 토큰 버킷 하나를 같이 쓴다.
  초당 한도는 모의투자(vts) 기준이다 (§62.3) → kis.rest.rate-limit-per-second (KIS 공지 확인)
  지금의 kis.rest.request-interval (고정 1초 간격)을 대체한다.
- 폴링 대상은 우선순위 순으로 돌린다. 한 바퀴 걸린 시간을 지표로 남긴다.
- 여러 종목을 한 번에 받는 KIS API(관심종목 멀티 시세 등)가 있으면 우선 쓴다.
  TR ID와 한 번에 받을 수 있는 종목 수는 KIS 문서로 확인한다.
- FOCUS인데 실시간에 못 든 종목은 호가도 폴링한다 (주기를 더 길게).
```

폴링·스냅샷으로 받은 시세도 실시간과 **같은 경로**를 탄다 (`QuoteIngestService` → Valkey → `market.price.updated` → WebSocket).
시세에 출처를 싣는다: `source = REALTIME | POLLING | SNAPSHOT`. 화면은 REALTIME이 아니면 "지연"으로 표시할 수 있다.

수요 상태:

```text
- 다시 만들 수 있는 상태라 Valkey에 둔다 (§18). 인스턴스가 죽어도 유령 수요가 남지 않게 TTL을 건다.
- 시청자    세션이 SUBSCRIBE · UNSUBSCRIBE · 종료할 때 갱신. 하트비트로 TTL을 연장한다.
- 주문      주문 ID 집합. order.accepted 에 SADD, 종결 이벤트(execution.completed, order.cancelled,
            order.rejected, order.expired)에 SREM. 집합이라 중복 이벤트에 안전하다.
- 토픽 사이에는 순서가 없다. 종결 이벤트가 접수 이벤트보다 먼저 도착할 수 있다.
  trading-service가 미체결 주문 스냅샷(order.open.snapshot)을 주기적으로 내고,
  market-service는 이것으로 주문 집합을 통째로 다시 맞춘다.
```

Mock 모드:

```text
- 같은 Subscription Manager를 쓴다. Mock 어댑터가 KIS의 한도(실시간 슬롯, REST 초당 한도)를 설정으로 흉내 낸다.
  → 계층 전환 로직을 KIS 없이 테스트할 수 있다.
- 실시간 계층 종목은 매 틱(market.mock.tick-interval), 폴링 계층 종목은 폴링 주기로 가격을 만든다.
- 출발 가격은 마스터의 base_price. Mock 가격도 호가단위에 맞춘다 (§59.1).
- Seed 또는 고정 시퀀스로 재현할 수 있어야 한다 (§31).
```

> **§67 →** Phase 8-2 전에 시청 수요(`market:demand:viewers`)와 결정형 모의 시세를 먼저 넣었다.
> 수요 키는 종목별 키가 아니라 정렬 집합 하나다. KIS 폴링 · 스냅샷 계층이 들어오면 모의 시세는 그 계층 밖의 종목에만 남는다.

## 57.3 프론트엔드

```text
- 종목 목록은 검색 + 페이지다. 전체 목록을 한 번에 받지 않는다.
- WebSocket 구독
    FOCUS  선택한 종목 1개 (현재가 · 호가 · 차트)
    LIST   지금 화면에 보이는 목록 행 + 관심종목
  화면에서 사라진 종목은 UNSUBSCRIBE 한다.
- 관심종목·목록의 첫 가격은 배치 조회로 채운다 (GET /api/market/prices?symbols=..., 최대 50).
- 세션당 구독 종목 수에 상한이 있다 (market.websocket.max-symbols-per-session). 넘으면 서버가 ERROR를 보낸다.
- 시세 source가 REALTIME이 아니면 지연 표시를 둔다. 표시 방식은 Figma를 따른다 (§30).
```

---

# 58. Kafka 체결 엔진

## 58.1 원칙

```text
- 주문 "접수"와 "체결"을 나눈다. 접수는 API가 동기로, 체결은 엔진이 비동기로 한다.
- MySQL이 원본이다. Kafka 메시지는 엔진을 깨우는 신호다.
  메시지가 사라지거나 두 번 와도 주문 상태 머신과 계좌 락이 정합성을 지킨다.
- 엔진은 상태를 메모리에 들고 있지 않는다 (인메모리 호가창 없음).
  재시작이나 리밸런스 때 복구할 것이 없다.
- 엔진은 trading-service 안의 모듈(engine/)이다 (§6.3). 별도 서비스로 쪼개지 않는다.
```

## 58.2 흐름

```text
[접수]  POST /api/trading/orders                    trading API, 트랜잭션 하나
          계좌 락 → 멱등 확인 → 검증(§59) → 예약 → orders INSERT (ACCEPTED)
          → outbox: order.created, order.accepted
          ← 202 Accepted { status: "ACCEPTED" }
        즉시 판단할 수 있는 거절(잔고·수량 부족, 검증 실패, 장외, 거래정지)은
        지금처럼 REJECTED로 저장하고 4xx + 공통 에러로 답한다.

[전달]  Outbox Publisher → Kafka order.accepted     key = symbol

[체결]  체결 엔진                                    consumer group: trading-engine
          입력  order.accepted, market.price.updated  둘 다 key = symbol
          order.accepted 수신       그 주문 하나를 최신 시세(Valkey)로 판정한다
          market.price.updated 수신 그 종목의 체결 가능한 ACCEPTED 주문을 모두 판정한다
                                    (시세를 기다리는 시장가 + 가격 조건을 넘은 지정가)

        판정 → 체결은 주문 한 건당 트랜잭션 하나:
          계좌 락 → 주문 다시 읽기 (아직 ACCEPTED인가) → 체결가 결정 → 예약 해제
          → executions · 계좌 · 포지션 · 원장 → 주문 FILLED → outbox 이벤트

[확인]  클라이언트는 지금처럼 주문·체결 조회로 결과를 확인한다 (useFillWatcher).
```

지금의 `PendingOrderExecutionService.execute`가 "주문 한 건 체결" 단위다. 엔진은 이것을 재사용하고 시장가로 넓힌다.
`PlaceOrderService`에서는 체결 코드를 걷어낸다. 체결 경로는 엔진 하나만 남는다.

## 58.3 시장가 주문

```text
체결가     체결을 판정한 시세의 가격 (§11). 그 시세는 market.price.max-age 안이어야 한다.

시세 대기  접수 시점에 신선한 시세가 없을 수 있다 (스냅샷 계층 종목).
           → market-service가 order.accepted(시장가)를 보고 그 종목을 즉시 스냅샷 + 최우선 폴링한다 (§57.2)
           → 새 시세가 market.price.updated로 오면 엔진이 체결한다
           → trading.market-order.max-wait (예: 5초) 안에 신선한 시세가 안 오면
             REJECTED(MARKET_PRICE_UNAVAILABLE)로 바꾸고 예약을 푼다.
             주기 작업이 오래된 ACCEPTED 시장가 주문을 찾아 처리한다.

매수 예약금 기준가격 × 수량 × (1 + trading.market-order.reserve-buffer-rate)
             기준가격 = 접수 시점 최신 시세, 없으면 상한가 (§59.2)
           체결대금이 (이 주문의 예약금 + 주문 가능 금액)을 넘으면 REJECTED(INSUFFICIENT_BALANCE).
           체결 후 남은 예약금은 돌려준다.
           → 체결이 늦어져 가격이 올라도 예수금은 음수가 되지 않는다 (§2).
```

## 58.4 토픽 · 파티션 (§16 추가분)

새 토픽:

```text
order.rejected        엔진이 거절했을 때 (시장가 시세 대기 초과, 체결 시점 잔고 부족)
order.expired         장 마감으로 만료됐을 때 (§59.3)
order.open.snapshot   미체결 주문이 있는 종목과 주문 ID. 주기 발행 (§57.2)
order.accepted.dlq    엔진이 재시도한 뒤에도 처리하지 못한 접수 이벤트
```

메시지 키:

```text
order.*                  key = symbol            ← 바뀜 (지금은 orderId)
execution.completed      key = symbol            ← 바뀜 (지금은 executionId)
market.price.updated     key = symbol            유지
account.* · ledger.*     key = accountId         유지
position.changed         key = accountId:symbol  유지
```

한 주문의 이벤트는 모두 같은 종목이다. 키를 종목으로 바꿔도 주문별 순서는 그대로 지켜진다.

```text
- 토픽은 브로커 자동 생성에 맡기지 않는다. 코드(TopicBuilder)로 만들고 파티션 수는 설정값으로 둔다.
- order.accepted 와 market.price.updated 는 파티션 수를 같게 둔다.
  엔진은 두 토픽을 리스너 하나로 구독하고 RangeAssignor를 명시한다.
  → 같은 종목의 주문과 시세를 같은 컨슈머 스레드가 처리한다.
  정합성은 DB 락이 지킨다. 이건 같은 주문을 두 스레드가 동시에 잡는 낭비를 줄이는 최적화다.
- 파티션 수를 바꾸면 키 → 파티션 대응이 바뀐다. 바꿀 때는 엔진을 멈추고 바꾼다.
```

## 58.5 실패 · 중복 · 복구 (§17 추가분)

```text
market.price.updated  처리에 실패하면 건너뛴다. 다음 틱이 온다 (지금과 같다).
order.accepted        백오프 재시도 후 order.accepted.dlq. 처리한 뒤에만 오프셋을 넘긴다.
안전망                주기 작업이 DB에서 오래된 ACCEPTED 주문을 다시 판정한다.
                      Kafka 메시지가 사라져도 주문은 결국 처리된다.
중복                  같은 이벤트가 두 번 와도, 엔진이 여러 개여도
                      "계좌 락 → 주문이 아직 ACCEPTED인가" 확인이 두 번째를 막는다 (ADR-0011).
취소와 체결의 경합     취소 API도 계좌 락부터 잡는다. 먼저 잡은 쪽이 이기고 나중 쪽은 상태를 보고 물러난다.
```

## 58.6 API 변경 (§24 추가분)

```text
POST /api/trading/orders
  신규 접수                    202 Accepted, status = ACCEPTED (시장가 포함)
  같은 Idempotency-Key 재요청  202, 그 주문의 현재 상태 (이미 FILLED일 수 있다)
  즉시 거절                    지금과 같다. 4xx + 공통 에러, REJECTED 주문은 기록에 남는다

프론트 (OrderForm)
  지금은 응답이 FILLED면 체결, 아니면 "지정한 가격 조건을 기다리고 있어요"로 안내한다.
  시장가 ACCEPTED는 "체결 중"으로 따로 안내한다. 체결 알림은 지금처럼 useFillWatcher가 낸다.
```

---

# 59. 거래 규칙 – 호가단위 · 가격제한폭 · 장 운영시간

## 59.1 호가단위

KRX 2023년 개편 기준 (KOSPI · KOSDAQ 주권 공통). 지금 `KrxTickSize`에 있는 표다.

```text
           가격 <   2,000     1원
  2,000 ≤ 가격 <   5,000     5원
  5,000 ≤ 가격 <  20,000    10원
 20,000 ≤ 가격 <  50,000    50원
 50,000 ≤ 가격 < 200,000   100원
200,000 ≤ 가격 < 500,000   500원
500,000 ≤ 가격           1,000원
```

```text
- 지정가는 호가단위의 배수여야 한다. 아니면 거절 (INVALID_TICK_SIZE).
- KrxTickSize를 common으로 옮긴다. market(Mock 가격)과 trading(주문 검증)이 같은 코드를 쓴다.
- 제도는 바뀔 수 있다. 표는 코드 한 곳에 두고 경계값 테스트(1,999 / 2,000 / 4,999 / 5,000 …)로 고정한다.
```

## 59.2 가격제한폭

```text
상한가   기준가 × 1.3 이하인 가장 큰 유효 호가
하한가   기준가 × 0.7 이상인 가장 작은 유효 호가
```

```text
- 지정가는 [하한가, 상한가] 안이어야 한다. 아니면 거절 (PRICE_LIMIT_EXCEEDED).
- 호가단위로 맞추는 방식(절사·절상, 어느 가격대의 호가단위를 쓰는지)은 KRX 업무규정 원문으로
  확인한 뒤 경계값 테스트로 고정한다. 확인 전에는 위의 보수적인 정의(±30%를 넘지 않는다)를 쓴다.
- 기준가는 종목 마스터의 base_price다 (§57.1).
- trading은 market을 동기 호출하지 않는다 (§6.2). market-service가 Valkey market:meta:{symbol} 에
  종목 메타(기준가 · 거래정지 · 상장 여부)를 두고 trading이 읽는다. ADR-0008과 같은 방식이다.
- 메타가 없으면(동기화 전) 가격제한 검증은 건너뛰고 경고를 남긴다. 주문은 막지 않는다.
```

## 59.3 장 운영시간 · 거래정지

```text
trading.session.enforce   kis 모드 기본 true, mock 모드 기본 false
                          (Mock은 아무 때나 거래 흐름을 연습할 수 있어야 한다, §0-4)
정규장                    09:00 ~ 15:30 KST (설정값)
휴장일                    설정 목록 또는 KIS 국내휴장일조회
```

`enforce=true`일 때:

```text
- 정규장 밖 주문은 거절한다 (MARKET_CLOSED).
- 장 마감 시각에 ACCEPTED 주문을 만료(EXPIRED)하고 예약을 푼다. 당일 주문만 지원한다.
```

항상:

```text
- 거래정지(trading_halted) · 상장폐지(listed=false) 종목은 주문을 거절한다 (SYMBOL_NOT_TRADABLE).
- 마스터에 없는 종목코드는 SYMBOL_NOT_FOUND.
```

> **Phase 9 →** 동시호가 · 시간외가 들어오면 "정규장 밖 거절"은 "거래 가능한 세션 밖 거절"로 넓어진다 (§63).

---

# 60. Phase 8 데이터 · 계약 변경 요약

## 60.1 DB

```text
market    stocks 신규 (§57.1)
trading   orders 인덱스 (symbol, status, order_type)   엔진이 틱마다 조회한다
          orders.status 에 EXPIRED 추가
          orders.reject_reason 에 엔진 거절 사유(MARKET_PRICE_UNAVAILABLE, INSUFFICIENT_BALANCE)가 들어간다
```

스키마는 Flyway 마이그레이션으로만 바꾼다 (ADR-0004).

## 60.2 Valkey 키 (§18 추가분)

```text
market:meta:{symbol}             종목 메타 (기준가, 거래정지, 상장 여부)
market:demand:viewers:{symbol}   시청자 (FOCUS / LIST 구분, TTL)
market:demand:orders:{symbol}    미체결 주문 ID 집합
market:subscription:state        현재 실시간 · 폴링 배정 (관측용)
```

## 60.3 에러 코드 (§39 추가분)

```text
INVALID_TICK_SIZE      400   지정가가 호가단위에 맞지 않는다
PRICE_LIMIT_EXCEEDED   400   지정가가 상한가 · 하한가 밖이다
MARKET_CLOSED          400   정규장 밖이다 (session.enforce=true)
SYMBOL_NOT_TRADABLE    400   거래정지 · 상장폐지 종목이다
```

원칙: 클라이언트가 고칠 수 있는 문제는 4xx, 잠시 뒤 해소되는 상태는 503.

## 60.4 REST · WebSocket

```text
GET  /api/market/stocks            페이지 객체로 바뀐다 (§57.1)                      프론트 수정 필요
GET  /api/market/stocks/{symbol}   standardCode, basePrice, upperLimit, lowerLimit,
                                   tickSize, tradable 추가
GET  /api/market/prices?symbols=   신규. 최대 50종목 배치 조회 (Valkey 우선, 없으면 스냅샷 요청)
GET  .../price                     source (REALTIME | POLLING | SNAPSHOT) 추가
POST /api/trading/orders           202 Accepted (§58.6)                               프론트 수정 필요
WS   SUBSCRIBE                     mode: FOCUS | LIST 추가 (생략하면 LIST)
```

API 명세(`docs/api/openapi.yaml`, `api-spec.md/.xlsx/.pdf`)는 생성물이다.
계약을 바꾸면 `./infra/scripts/generate-openapi.sh`로 다시 만들고,
스크립트의 §24 엔드포인트 목록(EXPECTED)에 새 엔드포인트를 더한다.
컨트롤러에는 `@Operation`, `@ApiErrorCodes`, DTO에는 `@Schema` 설명을 단다. 비어 있으면 스크립트가 멈춘다.

---

# 61. Phase 8 구현 순서

한 번에 다 하지 않는다 (§51). 단계마다 완료조건을 확인하고 다음으로 간다.

## 61.1 단계

### Phase 8-1 – 종목 마스터

> **완료 (2026-10-06).** ADR-0014, ADR-0018.

- `stocks` 테이블 · 마스터 파일 파서 · 동기화 작업 · 스냅샷 CSV와 생성 스크립트
- `StockRepository`를 DB 구현으로 교체하고 설정 `market.stocks`를 지운다
- 검색 API 페이지화, 종목 상세 필드 추가, 프론트 종목 검색 · 목록 수정
- §62 반영: `KIS_ENVIRONMENT=real` 기동 차단, `KIS_ACCOUNT_NO` 제거, "Phase 8에서 Cognito" 표기 9곳 정정 (§48). 화면 모의투자 표시(§62.5)는 로고에 이미 있다 (`LearnstockLogo`)

완료조건:

```text
전 종목을 검색할 수 있고, 설정 파일의 5종목이 사라진다. 네트워크 없이 Mock 모드가 뜬다.
```

### Phase 8-2 – 계층형 구독

- Subscription Manager · Valkey 수요 상태 · WebSocket `mode`
- KIS 어댑터: 고정 구독 → 동적 등록·해제, REST 토큰 버킷 · 폴링 · 스냅샷
- Mock 어댑터 계층 흉내, 배치 시세 API, 프론트는 보이는 종목만 구독

완료조건:

```text
아무 종목이나 상세를 열면 시세가 뜨고 갱신된다. 실시간 슬롯을 넘겨도 폴링으로 갱신된다.
```

### Phase 8-3 – Kafka 체결 엔진

- 토픽 명시 생성 · 메시지 키 변경 · 엔진 리스너(두 토픽) · 시장가 비동기화(202)
- 시장가 시세 대기 초과 처리 · 안전망 재판정 · DLQ · `order.open.snapshot`
- `PendingOrderExecutionService`를 엔진의 체결 단위로 재사용, `PlaceOrderService`에서 체결 코드 제거
- 프론트 시장가 접수 안내 문구

완료조건:

```text
§56.5의 체결 기준을 만족한다. Kafka를 멈춘 채 주문하고 다시 켜면 쌓인 주문이 체결된다.
```

### Phase 8-4 – 거래 규칙

- `KrxTickSize` common 이동, 지정가 호가단위 · 가격제한폭 검증, 종목 메타 Valkey
- 장 운영시간 · 장 마감 만료 · 거래정지 차단, 신규 에러 코드

완료조건:

```text
§59의 규칙마다 정해진 에러 코드로 거절된다.
```

## 61.2 테스트 (§35 · §36 추가분)

단위:

```text
- 마스터 파일 파서 (실제 파일에서 자른 고정 픽스처, cp949)
- 구독 우선순위 · 슬롯 배정 · 최소 유지 시간
- 토큰 버킷 (Clock 주입. 실제 시간에 의존하지 않는다)
- 호가단위 · 상하한가 경계값
- 시장가 예약금 · 체결 시점 잔고 재확인
```

통합 (Testcontainers):

```text
- 시장가 접수 202 → 시세 이벤트 → FILLED
- 시장가 시세 대기 초과 → REJECTED, 예약 해제
- 같은 order.accepted 두 번 · 엔진 두 개 동시 소비 → 체결 한 번
- 취소와 체결 경합 → 하나만 성공, 잔고 일치
- Kafka 중단 중 접수 → 복구 후 체결
- 폴링 계층 시세로 지정가 체결
```

## 61.3 남길 ADR

```text
ADR-0014  종목 마스터는 KIS 종목정보 파일로 동기화한다
ADR-0015  시세는 수요 기반 계층형 구독으로 공급한다 (§21 · ADR-0012의 고정 구독 대체)
ADR-0016  주문 접수와 체결을 나누고 Kafka 체결 엔진이 체결한다
ADR-0017  엔진 입력 토픽의 키를 종목코드로 하고 파티션 수를 맞춘다
ADR-0018  이 서비스는 모의투자 전용이다 (KIS는 시세 전용 · vts. Cognito는 공개 배포 직전. ADR-0006 상태 갱신)
```

## 61.4 Phase 8 다음

§63 로드맵으로 옮겼다.

---

# 62. 서비스 정의 – 무조건 모의투자

> **이 섹션은 다른 모든 섹션보다 우선한다.** 어떤 Phase에서도 바뀌지 않는다.

## 62.1 정의

```text
이 서비스는 모의투자 전용이다. 예외는 없다.

- 실제 주문 · 실제 계좌 · 실제 자금 · 실제 청산/결제는 없다. 앞으로도 만들지 않는다.
- 사용자의 돈은 가상 자금이다 (§9.1). 실제 입금 · 출금 · 환전은 없다.
- 체결은 모두 서비스 안의 체결 엔진이 시뮬레이션한다 (§58).
  거래소에도 증권사에도 주문이 나가지 않는다.
- 실제인 것은 시세뿐이다 (KIS). 시세는 읽기만 한다.
```

실제 체결이 일어나지 않으므로, 실제 증권사라면 조심스럽게 다룰 기능(신용 · 미수 · 공매도 · 파생)도
시뮬레이션으로 구현한다 (§63).

## 62.2 실제와 같게 하는 것 · 다른 것

실제와 같게 한다:

```text
시세 · 호가                                    실제 KIS 데이터
호가단위 · 가격제한폭 · 장 운영시간 · 세션 규칙   KRX 규정
수수료 · 세금 계산 구조                          값은 설정 (§44)
```

시뮬레이션이라 실제와 다르다. 화면과 문서에 숨기지 않는다:

```text
- 우리 사용자의 주문은 실제 시장에 영향을 주지 않는다. 시장 충격이 없다.
- 호가 잔량 기반 체결(Phase 9)에서는 같은 잔량을 여러 사용자가 동시에 소비할 수 있다.
- 사용자끼리 주문을 맞추지 않는다. 모든 체결은 실제 시세를 기준으로 판정한다.
```

## 62.3 KIS 사용 범위

```text
쓴다     접근토큰 · 실시간 승인키, 시세 REST, 실시간 WebSocket, 종목정보 파일, 휴장일 조회
안 쓴다  주문 · 정정 · 취소 · 잔고 · 계좌 API. 호출하는 코드를 만들지 않는다 (지금 코드에도 없다)
환경     모의투자(vts)만 쓴다. KIS_ENVIRONMENT=real 이면 기동을 막는다
계좌     KIS_ACCOUNT_NO는 필요 없다. 설정과 .env.example에서 뺀다
```

```text
- 모의투자 앱키로도 실제 시장 시세가 나온다 (지금 vts로 받고 있다).
- 다만 REST 초당 한도가 실전과 다를 수 있다. 폴링 계층(§57.2)의 처리량이 이 한도에 묶이므로
  한도는 KIS 공지로 확인해 설정(kis.rest.rate-limit-per-second)에 둔다.
- MARKET_PROVIDER=mock (KIS 없이 동작)은 그대로 유지한다 (§0-4). 오프라인 개발과 테스트용이다.
```

## 62.4 인증 – Cognito는 공개 배포 직전에

```text
- 지금은 Mock Login (HMAC 서명 토큰, ADR-0006)을 쓴다.
- Amazon Cognito는 나중에 붙인다. Phase 11(AWS 배포)의 첫 작업이다.
- Cognito를 붙이기 전에는 배포하지 않는다. 로컬 개발 환경에서만 돌린다.
  지금 Mock Login은 이메일만 알면 그 사용자로 들어갈 수 있기 때문이다.
- Cognito Multi-Region Replication(GCP로 전환해도 로그인이 되게 하는 것)은 DR 단계(Phase 12)에서 정한다.
- 그 전까지도 토큰 서명 · 만료 검증 · Gateway의 X-User-Id 위조 차단은 그대로 지킨다.
```

## 62.5 화면 표시

```text
- 모든 화면에서 모의투자임을 알 수 있게 한다. 문구와 위치는 Figma를 따른다 (§30).
- 실제 증권사 · 거래소로 오인할 수 있는 이름 · 로고 · 문구를 쓰지 않는다 (§53).
```

---

# 63. 전체 로드맵

> Phase 8(§56~§61) 다음 단계다. 이 문서가 미뤄 두었던 범위를 모두 넣었다
> (§3, §6.1, §38, §41, §48, §56.4).
>
> 여기서는 범위와 순서만 정한다. **각 Phase를 시작하기 전에 상세 설계 섹션을 이 문서 끝에 새 번호로 덧붙인다**
> (Phase 8의 §56~§61처럼). 상세 설계 없이 구현을 시작하지 않는다.

## 63.1 순서

| Phase | 내용 | 비고 |
|---|---|---|
| 8 | 전 종목 · 계층형 구독 · Kafka 체결 엔진 · 거래 규칙 | §56~§61 |
| 9 | 체결 현실화 · 세션 확장 · 체결 푸시 · 감사 이벤트 | |
| 10 | 운영 기반 (Rate Limit · 다중 인스턴스 · 관측성 · 품질 게이트) | |
| 11 | Cognito · AWS 배포 (Terraform · EKS · 관리형 데이터 · Argo CD) | Cognito 없이 배포하지 않는다 |
| 12 | GCP DR (GKE · 클라우드 간 복제 · 자동 전환 · RPO/RTO) | |
| 13 | 상품 확장 (ETF · ETN · ELW · 리츠 · 코넥스) | |
| 14 | 신용 · 미수 · 공매도 (결제 T+2 시뮬레이션) | |
| 15 | 파생상품 (KOSPI200 선물 · 옵션) | 범위가 가장 크다 |
| 16 | 모바일 앱 | |

클라우드(11~12)를 상품 확장(13~15)보다 먼저 둔다. 이 프로젝트의 핵심 메시지(§55)가
클라우드 네이티브와 멀티클라우드 DR이기 때문이다.

### Phase 9 – 체결 현실화

```text
호가 기반 체결   시장가는 실제 상대 호가를 1단계부터 잔량만큼 소비한다. 지정가는 지정가 이내 잔량까지만 체결한다.
                 남은 수량은 다음 호가 갱신 때 이어서 판정한다 → PARTIALLY_FILLED (§9.6). 체결 기록은 건마다 남긴다.
                 신선한 호가가 없으면 Phase 8처럼 시세 기준 전량 체결로 대신한다.
                 취소는 남은 수량만 취소하고 남은 예약만 푼다.
동시호가         08:30~09:00 · 15:20~15:30에 접수한 주문은 실제 시가 · 종가로 한 번에 판정한다.
                 예상체결가 실시간 제공 여부는 KIS 문서로 확인한다.
시간외           장전 시간외 종가 · 장후 시간외 종가 · 시간외 단일가.
                 시간대와 가격 규칙은 KRX 기준으로 확인해 설정에 둔다.
                 §59.3의 "정규장 밖 거절"은 "거래 가능한 세션 밖 거절"로 넓어진다.
체결 푸시        사용자별 WebSocket 채널. 브라우저 핸드셰이크에는 헤더를 실을 수 없으므로(ADR-0007)
                 REST로 수명이 짧은 연결 티켓을 받아 연결할 때 낸다. 원천은 Kafka의 주문 · 체결 이벤트다.
감사 이벤트      §41의 audit.logged. 아직 구현되지 않았다. LOGIN_SUCCESS부터 POSITION_CHANGED까지.
```

### Phase 10 – 운영 기반

```text
Rate Limiting          Gateway RequestRateLimiter + Valkey. §6.1의 "구조만 마련"을 실제로 적용한다.
market 다중 인스턴스    KIS 연결은 앱키당 하나다 → 리더 한 대만 수집한다.
                       → §66에서 리더 선출 대신 앱 분리(market-ingest replica 1 · market-service HPA)로, WebSocket 송신은
                         Kafka 대신 Valkey pub/sub로 풀었다 (ADR-0022).
                       WebSocket 송신은 각 인스턴스가 market.price.updated를 소비해서 한다.
                       리더 선출 방식은 ADR로 정한다. 계좌 정합성 락이 아니므로 §13과 무관하다.
관측성                  OpenTelemetry → Tempo (트레이스), Micrometer → Prometheus → Grafana (지표),
                       구조화 로그 → Loki. 로컬은 docker compose 프로파일로 띄운다.
                       traceId(§40)를 트레이스와 잇는다.
품질 · 배포 준비        SonarQube, 이미지 레지스트리 푸시, GitOps 저장소 갱신 (§38)
```

### Phase 11 – Cognito · AWS 배포

```text
Cognito     User Pool로 로그인, Gateway가 Cognito JWT를 검증한다. Mock 토큰을 대체한다. 이 Phase의 첫 작업이다
Terraform   VPC · EKS · RDS MySQL (Multi-AZ) · ElastiCache (Valkey) · MSK · ECR
배포        GitLab CI가 ECR에 이미지를 올리고 GitOps 저장소를 갱신한다 → Argo CD가 EKS에 동기화한다
비밀값      KIS 앱키 · AUTH_TOKEN_SECRET은 클라우드 비밀 저장소에서 주입한다 (§33). Git에 두지 않는다
전제        Cognito가 먼저다. Cognito 없이 공개 배포하지 않는다 (§62.4)
```

### Phase 12 – GCP DR

```text
구성   GKE · Cloud SQL (MySQL) · Memorystore (Valkey) · Kafka (관리형 또는 자체 운영, ADR로 정한다)
복제   MySQL   AWS 원본 → GCP 복제본 (클라우드 간 복제)
       Kafka   단방향 미러링 (MirrorMaker 2 등)
       Valkey  복제하지 않는다. 다시 만들 수 있는 캐시라 전환 뒤 다시 채운다 (§18)
전환   상태 검사 실패 → DNS 전환 → GCP 복제본 승격 → GCP 쪽이 KIS 수집 리더가 된다
측정   RPO · RTO를 장애 훈련으로 측정하고 목표값을 둔다 (예: RPO ≤ 1분, RTO ≤ 15분. 측정한 뒤 확정한다)
인증   Cognito는 AWS 리전 서비스다. GCP로 전환해도 로그인이 되는 방법(Cognito MRR 등)을 상세 설계에서 정한다
방식   Active-Passive. 쓰기 지점은 언제나 한 곳이다
```

### Phase 13 – 상품 확장

```text
ETF · ETN · ELW · 리츠 · 코넥스
- 종목정보 파일의 해당 레코드와 코넥스 파일을 마스터에 넣는다 (§57.1의 "주권만"을 푼다)
- 상품마다 호가단위 · 가격제한폭이 다르다. KRX 규정으로 확인하고 상품별로 테스트한다
- §59의 규칙 코드를 상품 유형별로 고를 수 있게 넓힌다
```

### Phase 14 – 신용 · 미수 · 공매도

```text
결제 T+2   지금은 체결하는 순간 예수금이 움직인다. 예수금 · D+1 · D+2 예수금과 결제일 정산을 시뮬레이션한다.
           미수와 신용의 전제다.
미수       종목별 증거금률로 매수한다. D+2에 미납이면 반대매매한다.
신용       보증금률 · 신용이자(일할) · 만기 · 담보비율 미달 시 반대매매
공매도     대차 가능 종목 · 수량 풀(시뮬레이션), 차입 수수료, 호가 제한 규칙, 상환
공통       비율 · 이율 · 한도는 모두 설정값이다. 반대매매도 체결 엔진(§58)을 거친다.
           "예수금은 음수가 될 수 없다"(§2)는 "미수 · 신용 한도 안에서만"으로 다시 정의한다. 상세 설계에서 확정한다.
```

### Phase 15 – 파생상품

```text
KOSPI200 선물 · 옵션. 주식과 다른 도메인이다.
- 시세: KIS 국내선물옵션 실시간 · REST. 모의투자(vts) 환경이 어디까지 주는지 먼저 확인한다
- 증거금(개시 · 유지), 일일정산, 만기 · 최종결제, 선물옵션 전용 가상 계좌
- trading-service 안의 별도 모듈로 두되, 주식 계좌 · 잔고와 섞지 않는다
- 범위가 가장 크다. 상세 설계에서 1차 범위(예: 선물만)를 다시 자른다
```

### Phase 16 – 모바일 앱

```text
- 같은 Gateway API · WebSocket 계약을 쓴다. 앱 전용 서버 API를 따로 만들지 않는다.
- 기술 선택(React Native 등)과 배포 방식은 ADR로 정한다. 디자인은 Figma를 따른다 (§30).
- 체결 알림은 Phase 9의 체결 푸시를 그대로 쓴다. 앱 푸시 알림은 상세 설계에서 정한다.
```

## 63.2 하지 않는 것

| 항목 | 이유 |
|---|---|
| 실제 주문 · 계좌 · 자금 · KRX 직접 연결 · 청산/결제 | 모의투자 전용이다 (§62.1) |
| 사용자 간 주문 매칭 | 실제 시세와 다른 가격이 생긴다. 체결은 실제 시세 기준이다 (§62.2) |
| Active-Active 멀티클라우드 | 거래 DB의 쓰기 지점은 하나여야 한다 (§0-6). DR은 Active-Passive다 (Phase 12) |
| RPO 0 보장 | 클라우드 간 동기 복제가 필요하다. 대신 측정하고 목표를 둔다 (Phase 12) |
| KIS 앱키 여러 개로 구독 상한 우회 | KIS 이용 정책을 확인하지 않은 우회다. 계층형 구독으로 해결한다 (§57.2) |
| Event Sourcing · Full CQRS · Saga · 2PC · 분산 트랜잭션 · Service Mesh | 기능이 아니라 설계 선택이다. §46을 유지한다 |

---

# 64. EKS 내부 검증 배포

> Phase 8과 별개로, 앱을 trader-gitops의 두 EKS 클러스터에 올려 Kafka · MySQL 연동을 확인하는 배포다.
> **§62.4의 "Cognito 없이 배포하지 않는다"는 이 배포에 적용하지 않는다** (ADR-0020). 조건은 시험 계정만 쓰고 실사용자 · 실제 개인정보를 받지 않는 것이다.

## 64.1 범위

```text
- 접속: VPC 셸의 kubectl port-forward, 그리고 기존 Terraform 진입점(CloudFront → internal ALB → frontend,
  TargetGroupBinding)에 frontend만 붙인 시험용 경로 (ADR-0020 6항).
- 인증은 Mock Login 그대로다. Cognito를 쓰지 않는다. 그래서 진입점으로는 시험 계정만 쓴다. 실사용자 · 실제 개인정보를 받지 않는다.
  실사용자를 받기 전에는 실제 인증을 붙인다 (§62.4, Phase 11).
- 배포 정의는 trader-gitops 저장소에 있다 (applications/<tier>/wts/). 이 저장소는 이미지와 설정 계약만 책임진다.
```

## 64.2 배치

| 서비스 | tier |
|---|---|
| frontend · gateway-service · user-service · market-service | channel |
| trading-service | corebank |

Valkey는 tier마다 하나씩 둔다. market-service · trading-service는 replica 1이다 (ADR-0020).

> **→ §66 (ADR-0022):** market은 별도 앱 · 이미지 `market-ingest`(수집, replica 1)와 `market-service`(조회 · WebSocket, HPA)로 나뉘고, trading-service도 HPA로 늘어난다. channel에 market-ingest가 추가된다.

## 64.3 이미지

```text
- 서비스마다 backend/<서비스>/Dockerfile. 멀티스테이지, 이미지 안에서 Gradle로 jar를 만든다 (ADR-0019).
- 실행 사용자는 숫자 UID 10001이다.
- 푸시는 CI가 아니라 ./infra/scripts/push-images.sh <channel|corebank> <태그> 로 한다. 태그는 불변이고 latest를 쓰지 않는다.
```

## 64.4 2단계 – 설계 전

channel → corebank는 internal ALB 443 하나뿐이고 Kafka는 tier를 넘지 못한다. 그래서 1단계에서는
trading-service가 시세를 받지 못하고, gateway에서도 닿지 않는다. 인증서 · ALB · 시세 중계 ·
market ← trading 수요 신호(§57.2) 방향은 상세 설계를 이 문서 끝에 새 번호로 덧붙인 뒤 구현한다 (ADR-0020, §63).

> **→** gateway → trading은 trader-terraform-sessac ADR-0046(develop internal ALB)으로 풀었다. 시세 중계는 §65 (ADR-0021).
> 수요 신호는 §65.6의 방향 규칙을 따라 Phase 8-2 상세 설계에서 정한다.

---

# 65. tier 간 시세 중계 – channel → corebank

> §64.4의 2단계 중 "trading이 체결 판정에 쓰는 시세"를 푼다. ADR-0021.
> 로컬(docker compose)은 한 tier라 이 기능을 끈다. 켜지 않으면 동작이 지금과 같다.

## 65.1 문제

```text
- trading-service(corebank)는 최신 시세를 자기 tier의 Valkey(ADR-0008)에서, 시세 이벤트를 자기 tier의 Kafka에서 읽는다.
- 시세를 만드는 market-service는 channel에 있다. Valkey · Kafka는 tier마다 따로다.
- 네트워크: channel → corebank는 develop internal ALB 443(HTTPS) 하나뿐이다. corebank → channel은 막혀 있다.
  Kafka(TCP)는 tier를 넘지 못하고, corebank에는 인터넷이 없어 KIS를 직접 받을 수 없다.
→ corebank에 시세가 없어 모든 주문이 MARKET_PRICE_UNAVAILABLE로 거절된다.
```

## 65.2 원칙

증권사의 시세 분배 구조와 망분리 관행을 따른다 (조사 출처는 ADR-0021).

```text
1. 시세는 수신 구역(channel) → 거래 구역(corebank) 한 방향으로 흐른다.
2. 연결은 언제나 channel이 연다. corebank는 바깥으로 세션을 열지 않는다.
3. 경계의 통로는 하나다. 이미 있는 ALB 443에 경로 하나를 더하고, 요청마다 서명으로 인증한다.
4. 받는 쪽은 수신 게이트웨이가 받아 자기 tier의 Valkey · Kafka에 다시 낸다.
   그래서 trading의 기존 읽기 경로(ADR-0008, §12, §58)는 바뀌지 않는다.
5. 시세는 다시 만들 수 있다 (§18). 유실보다 최신성이 중요하다. 밀린 것은 버리고 종목별 최신값만 보낸다.
```

## 65.3 흐름

```text
[channel]  market-service
             시세 수신 → Valkey · Kafka market.price.updated (지금과 같다)
             feed relay (신규, market.feed-relay.enabled=true 일 때만)
               consumer group market-service-feed-relay 로 market.price.updated 를 묶어서 소비
               묶음 안에서 종목별 마지막 이벤트만 남기고, max-age(§11)를 넘긴 것은 버린다
               → HTTPS POST https://trading.internal.cloudnetwork.kr/internal/market/prices  (서명 헤더)
               실패하면 로그만 남기고 그 묶음을 버린다. 재시도하지 않는다. 다음 시세가 온다.

[corebank] trading-service
             market feed 수신 (신규, trading.market-feed.enabled=true 일 때만)
               서명 · 시각 검증 → 이벤트마다
                 형식 검사 (eventType, 종목코드, 가격 > 0, timestamp)
                 max-age 를 넘겼으면 버린다
                 Valkey market:price:{symbol} 의 timestamp 보다 새롭지 않으면 버린다
                 Valkey market:price:{symbol}       ← market-service 와 같은 형식으로 쓴다
                 Kafka  market.price.updated key=symbol ← 받은 이벤트 봉투를 그대로 낸다 (eventId · traceId 유지)
             이후는 지금과 같다
               시장가 주문 → ValkeyMarketPriceQuery
               미체결 지정가 → MarketPriceUpdatedConsumer (Phase 8 엔진도 같은 토픽, §58.4)
```

## 65.4 계약

```text
POST /internal/market/prices
  Content-Type      application/json
  X-Wts-Signature   t=<epoch seconds>,v1=<base64url(HMAC-SHA256(key, "<t>." + 본문 바이트))>
  본문              { "events": [ <market.price.updated 봉투 (§16)>, ... ] }   최대 500개
  응답 200          { "accepted": n, "skipped": m }
       401          서명이 없거나 틀렸거나, t 가 허용 오차(trading.market-feed.max-clock-skew, 기본 60초) 밖
       400          본문이 JSON 이 아니거나 events 가 없다. 이벤트 하나가 깨진 것은 skipped 로 센다
  경로              /internal/** 는 gateway 가 라우팅하지 않는다(/api/**, /ws/** 만). 공개 API(§24)가 아니므로
                    OpenAPI 명세에서 뺀다 (@Hidden).
  키                 Secrets Manager wts/channel/market-feed 와 wts/corebank/market-feed (같은 값),
                    JSON 키 MARKET_FEED_SECRET. 두 앱이 configtree 로 읽는다 (ADR-0020 7항).
  재전송             허용 오차 안에서 같은 요청을 다시 보내도 해가 없다. Valkey 는 더 새로운 값만 쓰고,
                    Kafka 중복은 주문 상태 머신이 막는다 (ADR-0011).
  시각 형식          이벤트의 occurredAt · payload.timestamp 는 숫자(epoch 초.나노)다. market-service 가
                    value-serializer: JsonSerializer 를 설정으로 지정해 Spring Kafka 기본 매퍼를 쓰기 때문이다.
                    읽는 쪽은 Jackson 으로 읽어 숫자와 ISO 문자열을 모두 받는다. Valkey 값은 ISO 문자열이다.
                    테스트 이벤트는 발행자와 같은 매퍼로 만든다 (2026-10-16 장애, ADR-0021).
```

서명은 `common`의 `FeedSignature`가 만든다 (MockAuthToken과 같은 HmacSHA256 · 상수 시간 비교).

## 65.5 설정

| 서비스 | 속성 | 환경변수 | 기본 |
|---|---|---|---|
| market | `market.feed-relay.enabled` | `MARKET_FEED_RELAY_ENABLED` | `false` |
| market | `market.feed-relay.url` | `MARKET_FEED_RELAY_URL` | (켜면 필수) |
| market | `market.feed-relay.secret` | `MARKET_FEED_SECRET` | (켜면 필수) |
| market | `market.feed-relay.connect-timeout` · `read-timeout` | | 2초 · 3초 |
| trading | `trading.market-feed.enabled` | `TRADING_MARKET_FEED_ENABLED` | `false` |
| trading | `trading.market-feed.secret` | `MARKET_FEED_SECRET` | (켜면 필수) |
| trading | `trading.market-feed.max-clock-skew` | | 60초 |

켰는데 URL · 키가 비어 있으면 기동을 막는다 (KIS 자격증명과 같은 방식, §52).
묶음 크기는 Kafka fetch 설정(`fetch.max.wait.ms` 300ms)으로 정한다. 타이머를 따로 두지 않는다.

## 65.6 방향 규칙과 이후

```text
- tier 경계를 넘는 연결은 언제나 channel 이 연다. corebank 에서 channel 로 가는 길을 열지 않는다.
- Phase 8-2의 수요 신호(order.accepted · order.open.snapshot → market)도 이 규칙을 따른다.
  channel 이 같은 ALB 로 corebank 에서 당겨 온다 (예: GET /internal/orders/open-snapshot). 상세는 Phase 8-2 설계에서 정한다.
- 호가(market:orderbook)는 중계하지 않는다. trading 이 쓰지 않는다. 호가 기반 체결(Phase 9)에서 다시 정한다.
- 로컬과 한 클러스터 배포에서는 두 서비스가 같은 Valkey · Kafka 를 쓰므로 끈다.
```

## 65.7 테스트

```text
단위
- FeedSignature: 서명 · 검증, 본문 1바이트 변조, 시각 허용 오차 경계, 형식이 깨진 헤더
- relay: 종목별 마지막 값만 남는다, 오래된 시세를 버린다, 전송 실패가 예외로 새지 않는다
- relay HTTP 클라이언트: 서명 헤더와 본문 (MockRestServiceServer)
통합 (trading, Testcontainers)
- 서명한 요청 → Valkey 에 시세 → 시장가 주문 체결
- 서명한 요청 → Kafka market.price.updated → 미체결 지정가 체결
- 틀린 서명 401, 오래된 시세 · 더 오래된 timestamp 는 skipped 이고 Valkey 가 바뀌지 않는다
```

## 65.8 인프라 (다른 저장소)

```text
trader-terraform-sessac  develop ALB 리스너 규칙에 /internal/market/prices 추가 (같은 대상 그룹 8082)
                         시크릿 wts/<tier>/market-feed 와 market · trading Pod Identity 권한 (ADR-0047 방식)
trader-gitops            market: MARKET_FEED_RELAY_ENABLED=true, MARKET_FEED_RELAY_URL, SecretProviderClass 에 market-feed
                         trading: TRADING_MARKET_FEED_ENABLED=true, SecretProviderClass 에 market-feed
                         런북: 값 넣기 · 확인 · 9절 2행 해결 표시
```

남는 위험: channel 안의 다른 파드도 이 ALB 경로에 닿는다. 서명 키가 없으면 시세를 넣을 수 없지만,
키가 새면 가짜 시세로 체결을 일으킬 수 있다. 키는 두 앱만 읽는다 (Pod Identity). 교체 절차는 런북에 둔다.

---

# 66. 오토스케일링을 위한 분리 – market-ingest / market-service, trading 다중 인스턴스

> 트래픽에 맞춰 파드 수를 늘리고 줄이기(HPA) 위한 설계다. ADR-0022.
> §63 Phase 10의 "market 다중 인스턴스"를 리더 선출 없이 **앱(모듈 · 이미지) 분리**로 먼저 푼다.

## 66.1 문제

```text
market-service 한 프로세스에 "하나만 돌아야 하는 것"과 "사용자 수만큼 늘어야 하는 것"이 섞여 있었다.
  하나만      KIS 연결(앱키당 1개, 두 개면 OPSP8996 ALREADY IN USE appkey) · Mock 시세 생성기
              종목 마스터 동기화 · 1분봉 집계와 DB 저장 · tier 간 시세 중계(§65)
  늘어야      REST 조회 · WebSocket 연결과 송신
→ replica 1에 묶인다 (ADR-0020 4항).

늘리면 깨지는 곳
  WebSocket 송신   수집 스레드가 자기 세션에 바로 보냈다. KIS를 받지 않는 인스턴스는 화면에 아무것도 못 보낸다.
                   호가는 Kafka에도 없다 (§16).
  진행 중 1분봉    CandleAggregator의 메모리에 있었다. 다른 인스턴스의 차트는 마지막 봉이 빈다.
  trading 수신     §65의 "더 새로운 값만" 비교가 원자적이지 않았다. 파드가 여럿이면 같은 종목 요청이 동시에 갈린다.

trading-service의 나머지는 이미 다중 인스턴스에 안전하다.
  Outbox Publisher는 FOR UPDATE SKIP LOCKED, 주문은 계좌 락 · 멱등 키(§13, §14),
  시세 소비자는 컨슈머 그룹으로 파티션을 나눈다. 중복 체결은 상태 머신이 막는다 (ADR-0011).
```

## 66.2 모듈과 앱

`market-service`를 Gradle 모듈 셋으로 나눈다. 앱 둘은 이미지 · Dockerfile · ECR 리포가 따로다.

| 모듈 | 종류 | 가진 것 | 배포 |
|---|---|---|---|
| `market-core` | 라이브러리 (이미지 없음) | 도메인(MarketPrice · OrderBook · Stock · Candle), Valkey 캐시(`ValkeyQuoteCache`, `ValkeyCurrentCandleStore`), JPA 저장소(stocks · candles_1m), Flyway 마이그레이션, fan-out 채널 이름 | – |
| `market-ingest` | 앱 · 이미지 `apps/wts/market-ingest` | KIS · Mock 어댑터, `QuoteIngestService`(Valkey · Kafka · pub/sub 발행), 종목 마스터 동기화(스냅샷 zip 포함), 1분봉 소비 · 집계 · 저장, 시세 중계(§65) | `market-ingest`, replica 1, `strategy: Recreate` |
| `market-service` | 앱 · 이미지 `apps/wts/market-service` | REST(종목 · 현재가 · 호가 · 1분봉), 브라우저 WebSocket, pub/sub 구독 | `market-service`, HPA |

```text
- 패키지는 그대로 com.team.wts.market 이다. 클래스는 옮기기만 했다. 각 앱은 자기 클래스 + market-core 만 가진다.
- market-service(api)는 Kafka · KIS 의존성이 없다. market-ingest는 브라우저용 WebSocket 서버 · 컨트롤러 · OpenAPI가 없다.
  (KIS 실시간 수신용 WebSocket 클라이언트는 ingest에 있다.)
- 이름: 사용자 요청을 받는 쪽이 market-service 이름을 그대로 쓴다. gateway 라우트 · Service · OpenAPI 생성은 바뀌지 않는다.
- 두 앱은 같은 DB(wts_market) · 같은 Valkey를 쓴다. 쓰는 쪽은 ingest, 읽는 쪽은 api다 (예외: 없음).
  → §67: 예외 하나. 시청 수요 키(`market:demand:viewers`)는 api가 쓰고 ingest가 읽는다. 시세 값은 여전히 ingest만 쓴다.
  Flyway는 두 앱 모두 기동 시 적용을 시도한다. Flyway 잠금이 한쪽만 적용하게 한다.
- 로컬: run-backend.sh 가 둘 다 띄운다. market-ingest 포트는 MARKET_INGEST_PORT(기본 8084, health 확인용).
- Recreate: 옛 ingest를 완전히 내린 뒤 새 ingest를 띄운다. 그 사이 수 초 동안 시세가 멈춘다.
- ingest가 죽으면 Kubernetes가 다시 띄운다. 그동안 api는 마지막 시세(Valkey)를 보여 주고 송신만 멈춘다.
  ingest 이중화(리더 선출)는 하지 않는다. 필요해지면 다시 정한다.
```

## 66.3 WebSocket 송신 – Valkey pub/sub (§18 "WebSocket fan-out 보조")

```text
market-ingest   QuoteIngestService
                  시세  Valkey market:price:{symbol} 저장 → Kafka market.price.updated → PUBLISH market:fanout:price
                  호가  Valkey market:orderbook:{symbol} 저장                        → PUBLISH market:fanout:orderbook
                값은 Valkey 캐시와 같은 JSON(market-core의 MarketPrice · OrderBook)이다.
market-service  모든 인스턴스가 두 채널을 SUBSCRIBE → 받은 값을 자기 세션 중 그 종목을 구독한 세션에 보낸다.
                구독 스레드에서 순서대로 처리한다 (같은 종목 시세가 뒤바뀌어 나가지 않게).
```

```text
- 채널 이름은 market-core의 QuoteFanoutChannels 한 곳에 있다.
- pub/sub은 구독자 모두에게 간다. 인스턴스마다 컨슈머 그룹을 만들 필요가 없고, HPA로 파드가 늘고 줄 때 리밸런싱이 없다.
- 저장하지 않는다. api가 재시작하는 동안의 메시지는 잃는다. 클라이언트가 다시 SUBSCRIBE하면 Valkey 스냅샷을 먼저 보낸다
  (MarketWebSocketHandler, 지금과 같다). 시세는 다시 온다 (§18).
- 호가를 Kafka에 넣지 않는다는 결정(§16)은 유지한다.
```

## 66.4 진행 중 1분봉 – Valkey (§18 "현재 1분봉 상태")

```text
market-ingest   CandleAggregator가 봉을 갱신할 때마다 market:candle:1m:{symbol} 에 현재 봉 JSON을 쓴다. TTL 3분.
                봉을 확정해 DB(candles_1m)에 저장하는 것도 ingest 한 곳에서만 한다.
market-service  CandleQueryService.recent()가 진행 중 봉을 Valkey에서 읽어 확정 봉 뒤에 붙인다.
- TTL: ingest가 멈추면 오래된 "현재 봉"이 차트에 남지 않게 한다.
- Valkey를 읽지 못하면 확정 봉만 돌려준다.
```

## 66.5 trading 시세 수신 원자화 (§65 보완)

```text
- ValkeyMarketPriceMirror.saveIfNewer 는 Lua 스크립트 한 번이다.
    보조 키 market:price:seq:{symbol} (epoch 마이크로초) 보다 새로울 때만
    market:price:{symbol} 과 보조 키를 함께 쓰고 1을 돌려준다. 아니면 0.
- 값 형식(market:price:{symbol})은 그대로다. ValkeyMarketPriceQuery는 바뀌지 않는다.
- Kafka 재발행은 Lua가 1을 돌려줄 때만 한다. 더 오래된 시세가 corebank Kafka로 나가지 않는다.
- 두 키는 단일 노드 Valkey를 전제한다 (tier마다 하나, §64.2). 클러스터 모드로 바꾸면 해시 태그가 필요하다.
```

## 66.6 배포

```text
trader-terraform-sessac
  envs/ingress/registry   ECR 리포 wts/market-ingest 추가 (IMMUTABLE)
  envs/ingress/cluster    Pod Identity: market-ingest = db · kis · market-feed, market-service = db 만
trader-gitops
  applications/channel/wts/market-ingest/    새 앱. ServiceAccount market-ingest, SecretProviderClass(db · kis · market-feed),
                                             Deployment replicas 1 · Recreate. Service 없음 (사용자 요청을 받지 않는다)
  applications/channel/wts/market-service/   api만. SecretProviderClass(db), Deployment(replicas 없음), HPA min 1 · max 4 · CPU 70%
                                             Kafka · KIS · 중계 환경변수와 Kafka Secret 참조가 빠진다
  applications/corebank/wts/trading-service/ HPA min 1 · max 3 · CPU 70%
- 이미지: ./infra/scripts/push-images.sh channel <태그> 가 market-ingest도 만든다. 두 앱의 태그를 함께 바꾼다.
- HPA는 metrics-server(trader-terraform-sessac ADR-0040)를 쓴다. 노드는 Karpenter NodePool app이 늘린다.
- HPA가 줄이면 그 파드의 WebSocket이 끊긴다. 프론트가 다시 연결하고 스냅샷을 받는다.
```

## 66.7 테스트

```text
market-core     도메인 단위 테스트
market-ingest   수집 · 파서 · 집계 · 중계 단위 테스트
                통합(MySQL · Valkey · Kafka): 기동 시 마스터 적재, 틱 → Valkey, 틱 → pub/sub, 틱 → Kafka → 현재 1분봉(Valkey),
                브라우저용 WebSocket 설정이 없다
market-service  통합(MySQL · Valkey만): ingest가 쓰는 값을 market-core 저장소로 직접 넣고(MarketDataFixture) REST로 읽는다.
                pub/sub로 발행한 시세 · 호가가 WebSocket 클라이언트에 닿는다
trading         Lua: 순서가 뒤바뀐 쓰기 · 같은 종목 동시 쓰기에서 가장 새로운 값만 남는다
```

## 66.8 바뀌는 결정

```text
ADR-0020 4항 "market-service · trading-service는 replica 1"  → market-ingest만 replica 1, market-service · trading은 HPA
§6.2 "market-service" 한 서비스                               → market-ingest(수집) + market-service(조회 · 송신) + market-core
§63 Phase 10 "각 인스턴스가 market.price.updated를 소비해 송신" → Valkey pub/sub (호가가 Kafka에 없다)
§63 Phase 10 "리더 한 대만 수집(리더 선출)"                   → 수집을 별도 앱(replica 1)으로 분리
```

---

# 67. 결정형 모의 시세 – 실제 시세를 받지 않는 종목

> 고정 종목(§57.2, `market.pinned-symbols`) 밖의 종목에도 시세를 준다. ADR-0023.
> Phase 8-2의 KIS 수요 기반 구독 전까지의 빈칸을 채우고, Mock 모드의 random walk도 대체한다.

## 67.1 원칙

```text
1. 시세는 (종목코드, 기준가, 슬롯, seed)의 순수 함수다. 같은 순간이면 새로고침 · 재시작 · 파드 수와 상관없이 같은 값이다.
2. 계산하는 곳은 market-ingest 하나다 (§66.2). market-service(api)는 "누가 보고 있다"만 기록한다.
3. 실제 시세를 받는 종목(MarketDataProvider.realtimeSymbols())은 만들지 않는다. 한 종목에 실제와 모의가 섞이지 않는다.
4. 출처를 숨기지 않는다 (§62.2). 시세에 source = REALTIME | SIMULATED 가 실리고, 화면은 "모의 시세"로 표시한다.
5. 만든 뒤의 경로는 실제 시세와 같다 (QuoteIngestService → Valkey · Kafka · pub/sub → 1분봉 · 중계 · 체결).
```

## 67.2 모델 (market-ingest `quote/synthetic/`)

```text
StableHash           FNV-1a 64(종목코드) + SplitMix64. long 연산만. Random · String.hashCode · Math.sin · 부동소수점을 쓰지 않는다
SyntheticPriceModel  슬롯     n = floor(epochMillis / slot). 시세 시각 = 슬롯 시작 (지금 시각이 아니다)
                     등락     value noise 4옥타브 (1시간 ±600bp, 10분 ±200bp, 1분 ±60bp, 5초 ±15bp), 정수 선형보간
                     가격     기준가 × (10000 + bp) / 10000 → 상 · 하한가(±30%) 안 → KRX 호가단위 (KrxTickSize)
                     거래량   KST 자정부터의 누적. 하루 안에서 줄지 않는다
                     호가     현재가에서 호가단위씩 5단계, 잔량은 해시
                     기준가 = 전일 종가 = 종목 마스터 base_price (§57.1)
SyntheticQuoteFeed   대상 = (고정 종목 ∪ 시청 수요) − realtimeSymbols(). 종목별 마지막 슬롯을 기억해 같은 슬롯을 다시 내지 않는다
```

- 알고리즘 · 상수를 바꾸면 모든 종목의 시세 경로가 바뀐다. 골든 값 테스트가 막는다. 바꿀 때는 ADR-0023에 적는다.
- Mock 모드에는 실제 시세 공급자가 없다 (`MockMarketDataAdapter` 삭제). 고정 종목도 이 모델이 만든다.

## 67.3 시청 수요 (§57.2 · §60.2의 일부를 먼저 넣는다)

```text
Valkey  market:demand:viewers   정렬 집합. member = 종목코드, score = 만료 시각 (epoch ms)
쓰기    market-service  WebSocket SUBSCRIBE · GET .../price · GET .../orderbook 즉시
                        + 10초마다 이 인스턴스의 구독 종목 전체 (market.demand.heartbeat-ms)
                        수명 market.demand.viewer-ttl (30초). UNSUBSCRIBE는 지우지 않는다 (만료되게 둔다)
                        종목코드 형식(StockSymbol)이 아닌 값은 버린다
읽기    market-ingest   틱마다 만료분 삭제 → 최근 기록 순 최대 market.synthetic.max-symbols (100)
```

## 67.4 설정

| 앱 | 속성 | 환경변수 | 기본 |
|---|---|---|---|
| market-ingest | `market.synthetic.enabled` | `MARKET_SYNTHETIC_ENABLED` | `true` |
| market-ingest | `market.synthetic.slot` | `MARKET_SYNTHETIC_SLOT` | `1s` (`market.price.max-age`보다 짧아야 한다. 기동 시 검사) |
| market-ingest | `market.synthetic.tick-interval` | | 슬롯과 같다 |
| market-ingest | `market.synthetic.seed` | `MARKET_SYNTHETIC_SEED` | `0` (바꾸면 모든 경로가 바뀐다) |
| market-ingest | `market.synthetic.max-symbols` | `MARKET_SYNTHETIC_MAX_SYMBOLS` | `100` |
| market-service | `market.demand.viewer-ttl` | | `30s` |
| market-service | `market.demand.heartbeat-ms` | | `10000` |

`market.mock.*` (`MARKET_MOCK_TICK_INTERVAL`, `MARKET_MOCK_SEED`)는 없어졌다.

## 67.5 계약 변경

```text
GET /api/market/stocks/{symbol}/price   source (REALTIME | SIMULATED) 추가
WS  PRICE                               source 추가
Valkey market:price:{symbol}            source 추가. 없는 옛 값은 REALTIME으로 읽는다. trading은 모르는 필드를 무시한다
Kafka market.price.updated              바뀌지 않는다
```

## 67.6 제한

```text
- 아무도 보지 않는 종목의 미체결 지정가는 체결되지 않는다 (수요가 없으면 시세를 만들지 않는다).
  주문 수요는 Phase 8-2에서 §65.6의 방향 규칙을 따라 넣는다.
- 처음 여는 종목은 다음 틱(최대 1초)까지 현재가 조회가 503이다. WebSocket은 시세가 생기면 바로 받는다.
- 모의 시세는 24시간 움직인다. 정규장과 무관하다.
```

## 67.7 테스트

```text
단위   StableHash: FNV-1a 표준 벡터, 골든 값
       SyntheticPriceModel: 골든 값, 같은 입력 = 같은 출력 (인스턴스 · 반복), 슬롯 안 고정 · 시각 = 슬롯 시작,
                            호가단위 · 상하한, 거래량 단조 · KST 자정 리셋, seed · 종목에 따라 다른 경로
       SyntheticQuoteFeed: 실시간 종목 제외, Mock 모드 고정 종목, 수요 추가 · 마스터에 없는 종목 건너뜀,
                           같은 슬롯 1회, 재시작해도 같은 값
       ViewerDemandRecorder: TTL, 형식이 틀린 종목코드 버림 / MarketPrice: source 없는 옛 JSON
통합   ingest: 틱 → Valkey(SIMULATED) · pub/sub · Kafka → 1분봉, 수요 기록 → 다음 틱에 시세, 재시작 후 같은 시각 = Valkey 바이트 동일
       api: SUBSCRIBE → 수요 키, 현재가 응답의 source
```
