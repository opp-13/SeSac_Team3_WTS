# Phase 2 – Mock Market

CLAUDE.md §47 Phase 2의 산출물 정리.
**완료조건: WTS 화면에서 가격이 실시간으로 변한다.**

> **2026-10-19 갱신 (CLAUDE.md §67, ADR-0023):** `MockMarketDataAdapter`의 random walk는 결정형 모의 시세(`SyntheticQuoteFeed`, `SyntheticPriceModel`)로 바뀌었다. 아래는 Phase 2 당시 구조다.

## 시세 한 건이 흐르는 경로

```text
MockMarketDataAdapter (1초마다 tick)
        │  MarketDataListener 포트
        ▼
   QuoteIngestService
        ├─1─> Valkey   market:price:005930 / market:orderbook:005930
        ├─2─> Kafka    market.price.updated   (key = 종목코드)
        └─3─> WebSocket 구독 중인 세션
                                 │
                   Kafka ────────┘
                     │
                     ▼
              CandleAggregator ──> candles_1m (MySQL)
```

Valkey 저장이 가장 먼저다. WebSocket으로 새 가격을 받은 화면이 곧바로 REST를 호출해도
같은 값을 보게 하기 위해서다.

호가는 Kafka로 내보내지 않는다. 소비자가 없고 양만 많다 (§16의 토픽 목록에도 없다).

## Mock 시세 생성 (§31)

- 전일 종가에서 출발하는 random walk. 한 틱당 ±0.1% ~ 0.5%.
- 생성된 가격은 **KRX 호가단위**에 맞춘다. `80,123원` 같은 존재할 수 없는 값을 만들지 않는다.
- 상·하한가 ±30%를 벗어나지 않게 자른다.
- `market.mock.seed`를 주면 같은 흐름이 재현된다. 모든 테스트가 시드를 고정해 난수에 의존하지 않는다.

`KrxTickSize`는 Phase 3의 지정가 주문 검증에서도 그대로 쓴다.

| 가격대 | 호가단위 |
|---|---|
| ~ 2,000 | 1 |
| ~ 5,000 | 5 |
| ~ 20,000 | 10 |
| ~ 50,000 | 50 |
| ~ 200,000 | 100 |
| ~ 500,000 | 500 |
| 500,000 ~ | 1,000 |

## 거래량을 누적으로 다루는 이유

`MarketPrice.volume`은 틱 단위 증분이 아니라 **당일 누적 거래량**이다.
실제 KIS 실시간 체결 데이터도 누적값을 준다.

이 선택이 Kafka 소비의 중복 내성을 만든다 (§17). 1분봉 거래량을
`마지막 누적값 - 봉 시작 시점 누적값`으로 계산하므로, 같은 이벤트를 두 번 받아도
값이 부풀지 않는다. OHLC도 최대/최소/마지막값이라 중복에 안전하다.

덕분에 Phase 2에서는 `processed_events` 테이블 없이도 소비자가 멱등하다.
Phase 4에서 금액이 움직이는 소비자가 생기면 그때 도입한다.

## Kafka 이벤트 형식 (§16)

```json
{
  "eventId": "uuid",
  "eventType": "market.price.updated",
  "occurredAt": "2026-09-21T08:50:01.123Z",
  "traceId": "uuid 또는 null",
  "aggregateId": "005930",
  "version": 1,
  "payload": { "symbol": "005930", "price": 79800, "previousClose": 80000,
               "change": -200, "changeRate": -0.25, "volume": 159379,
               "timestamp": "2026-09-21T08:50:01.123Z" }
}
```

봉투(`DomainEvent`)는 `backend/common`에 있다. payload는 발행 서비스가 소유한다.

Kafka 타입 헤더를 붙이지 않는다(`spring.json.add.type.headers: false`).
붙이면 발행 측 클래스 이름이 소비 측 계약이 되어, 다른 서비스가 자기 payload 클래스로
읽을 수 없다. 소비자는 메시지를 문자열로 받아 직접 역직렬화한다.

메시지 키는 종목코드다. 같은 종목이 같은 파티션으로 가야 캔들 집계에서 순서가 보장된다.

## 1분봉 (§22)

`CandleAggregator`가 메모리에 종목별 진행 중인 봉을 들고 있다가,
분이 바뀌는 순간 직전 봉을 `candles_1m`에 저장하고 새 봉을 연다.

- 시세가 끊긴 종목은 30초마다 도는 sweep이 봉을 닫는다.
- 종료 시 `@PreDestroy`가 남은 봉을 저장한다.
- 조회 API는 확정된 봉(DB) + 진행 중인 봉(메모리)을 이어 붙여 돌려준다.
  그러지 않으면 차트의 마지막 1분이 비어 보인다.

`market:candle:1m:{symbol}` Valkey 키(§18)는 아직 쓰지 않는다.
진행 중인 봉을 읽는 쪽이 같은 JVM 안에 있어서 지금 쓰면 write-only 데이터가 된다.
market-service를 여러 인스턴스로 띄우는 시점에 필요해진다.

## 인증 경계

시세는 공개다. 배경은 [ADR-0007](../decisions/0007-market-data-is-public.md) 참고.

## 프론트엔드 (§28, §29)

```text
WtsPage
├── ConnectionStatus      WebSocket 연결 상태
├── Watchlist             5개 종목 + 실시간 가격
├── RealtimePrice         선택 종목 현재가 / 등락 / 누적거래량
└── OrderBookPanel        매도·매수 5단계
```

| 상태 | 도구 | 용도 |
|---|---|---|
| 종목 목록 | TanStack Query | 자주 바뀌지 않음, 5분 staleTime |
| 실시간 가격·호가 | Zustand `marketStore` | WebSocket이 밀어주는 값 |
| 연결 상태 | Zustand `websocketStore` | 상태만. 소켓 인스턴스는 모듈 스코프 |

`MarketSocket`은 끊기면 지수 백오프(0.5s → 최대 10s)로 재연결하고,
재연결 후 끊기기 전 구독을 복구한다.

등락 색상은 한국 관례를 따른다 — **상승 적색 / 하락 청색** (`tokens.css`).
시각은 UTC로 받아 `Asia/Seoul`로 표시한다 (§43).

## 구현 중 겪은 문제

**Gateway WebSocket이 연결은 되는데 메시지가 안 왔다.**
Phase 0에서 넣은 `DedupeResponseHeader`를 `default-filters`로 둔 것이 원인이었다.
업그레이드 핸드셰이크가 끝나면 응답 헤더가 읽기 전용이 되는데 필터가 이를 수정하려다
`UnsupportedOperationException`이 발생하고 연결이 끊겼다.
HTTP 라우트에만 개별로 붙이도록 바꿨고, `GatewayRouteConfigTest`가 재발을 막는다.

## Phase 2에 포함하지 않은 것

| 항목 | 도입 시점 |
|---|---|
| 차트 (TradingView Lightweight Charts) | Phase 5 |
| 라우팅(`/login`, `/wts/:symbol` 등 §26) | Phase 5 |
| 관심종목 등록/삭제 (§24) | Phase 5 |
| Dynamic Subscription (§21) | MVP 후반 |
| `market:candle:1m:*` Valkey 키 (§18) | 다중 인스턴스 시점 |
| `market:meta:*` Valkey 캐시 (§18) | 종목 마스터가 DB로 옮겨갈 때 |
| KIS 어댑터 | Phase 6 |

## 검증

```bash
docker compose up -d --wait
./infra/scripts/run-backend.sh
cd frontend && npm run dev
```

http://localhost:5173 에서 가격이 1초마다 갱신된다.

```bash
# 현재가가 실제로 변하는지
for i in 1 2 3; do curl -s localhost:8080/api/market/stocks/005930/price; echo; sleep 2; done

# Kafka를 거쳐 만들어진 1분봉
curl -s "localhost:8080/api/market/stocks/005930/candles?interval=1m"
```
