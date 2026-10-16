# ADR-0022: market을 수집 앱(market-ingest)과 조회 앱(market-service)으로 나누고 조회 앱만 오토스케일링한다

- 상태: 제안됨. EKS에서 market-service를 2개 이상으로 늘려도 시세 · 호가 · 차트가 갱신되고, `ALREADY IN USE appkey`가 없으면 채택으로 바꾼다
- 날짜: 2026-10-16
- 관련: CLAUDE.md §66, §65, §63 Phase 10, §18, §6.2. 갱신: ADR-0020 4항(replica 1), ADR-0019(서비스마다 Dockerfile, market-ingest 추가). 참조: trader-terraform-sessac ADR-0040(metrics-server), ADR-0047(앱별 Pod Identity)

## 배경

트래픽에 맞춰 파드를 늘리고 줄이려 한다(HPA). market-service 한 프로세스에는 두 종류가 섞여 있었다.
- **하나만 돌아야 하는 것:**
  - KIS 연결. 앱키당 하나다. 둘이면 KIS가 `OPSP8996 ALREADY IN USE appkey`로 거절한다(2026-10-15 롤링 배포에서 확인).
  - Mock 시세 생성기, 종목 마스터 동기화, 1분봉 확정 · 저장, tier 간 시세 중계.
- **사용자 수만큼 늘어야 하는 것:** REST 조회, WebSocket 연결과 송신.

그래서 replica 1에 묶여 있었다(ADR-0020 4항).

늘릴 때 깨지는 곳이 세 군데 있었다.
1. **WebSocket 송신:** 수집 경로 안에서 자기 세션에 직접 보냈다. KIS를 받지 않는 인스턴스는 보낼 것이 없다. 호가는 Kafka에도 없다(§16).
2. **진행 중 1분봉:** 메모리에 있었다(`CandleAggregator`).
3. **trading의 시세 수신(§65):** "더 새로운 값만" 비교가 원자적이지 않았다.

## 결정

1. **모듈 · 이미지를 나눈다.**
   - `market-core`(공유 라이브러리: 도메인 · Valkey 캐시 · JPA 저장소 · Flyway), `market-ingest`(수집 앱), `market-service`(조회 앱)다.
   - 앱마다 Dockerfile · ECR 리포 · 배포가 따로다. 각 이미지에는 자기 역할의 코드만 들어간다.
     - market-service에는 KIS · Kafka 의존성이 없다.
     - market-ingest에는 브라우저용 WebSocket 서버 · 컨트롤러가 없다.
   - 사용자 요청을 받는 쪽이 `market-service` 이름을 그대로 쓴다. gateway 라우트 · Service가 바뀌지 않는다.
2. **배포:**
   - `market-ingest`는 replica 1, Recreate다. 자기 ServiceAccount와 Pod Identity를 쓰고, db · kis · market-feed 시크릿을 읽는다.
   - `market-service`는 HPA다. db 시크릿만 읽는다.
3. **WebSocket 송신은 Valkey pub/sub이다(§18 "WebSocket fan-out 보조").** market-ingest가 `market:fanout:price`, `market:fanout:orderbook`에 PUBLISH하고, market-service 인스턴스가 모두 구독해 자기 세션에 보낸다.
4. **진행 중 1분봉은 Valkey `market:candle:1m:{symbol}`에 둔다(TTL 3분).** 확정 봉의 DB 저장은 market-ingest 한 곳에서만 한다.
5. **trading의 시세 수신 쓰기는 Lua 하나로 원자적으로 한다.**
   - 보조 키 `market:price:seq:{symbol}`보다 새로울 때만 쓴다.
   - 썼을 때만 Kafka로 다시 낸다.
   - trading-service도 HPA로 늘린다. 나머지 경로는 이미 다중 인스턴스에 안전하다: Outbox `SKIP LOCKED`, 계좌 락, 멱등 키, 컨슈머 그룹, 상태 머신.

## 검토한 대안

| 대안 | 버린 이유 |
|---|---|
| 같은 이미지에 역할 플래그(`MARKET_INGEST_ENABLED` 등)를 두고 Deployment 둘로 띄움 | 2026-10-16에 먼저 구현했다가 바꿨다. 이미지 하나에 두 역할의 코드 · 의존성(KIS · Kafka · WebSocket 서버)이 모두 들어간다. 설정 실수 하나로 조회 파드가 KIS에 붙을 수 있다. 배포 · 롤백 · 취약점 검사를 역할별로 따로 할 수 없다 |
| 리더 선출(Valkey 락 등)로 여러 market 인스턴스 중 한 대만 수집 | 수집을 replica 1 앱으로 떼면 선출이 필요 없다. 락 만료 · 이중 연결 경합을 다루지 않아도 된다. §13("Redis 분산 락부터 도입하지 않는다")의 정신과도 맞다 |
| 송신을 Kafka로(인스턴스마다 고유 컨슈머 그룹, §63 Phase 10 원안) | 호가가 Kafka에 없다(일부러, §16). 인스턴스마다 그룹이 생겼다 사라지고, HPA 때마다 오프셋 · 리밸런스를 다뤄야 한다. 송신은 저장이 필요 없다 |
| 중계(relay · 수신)만 별도 서비스로 분리 | 트래픽이 작아 확장 이득이 없다. market · trading 자체는 replica 1에 남는다 |

## 결과

- **좋은 점**
  - market-service와 trading은 부하에 따라 늘어난다.
  - KIS 연결은 언제나 하나다. Recreate라 배포 중에도 두 개가 되지 않는다.
  - 조회 앱 이미지에는 KIS · Kafka 코드와 KIS 키 마운트가 없다.
- **대가**
  - **모듈 · 배포 단위가 늘었다:** 이미지 · ECR 리포 · Argo CD 앱 · Pod Identity가 하나씩 늘었다(Terraform · gitops 변경).
  - **로컬:** `run-backend.sh`가 두 앱을 모두 띄운다.
- **같은 DB를 두 앱이 쓴다.** 쓰는 쪽은 market-ingest이고 market-service는 읽기만 한다. Flyway는 두 앱이 모두 시도하고, 잠금이 한쪽만 적용하게 한다.
- **pub/sub은 저장하지 않는다.** api 재시작 중의 송신은 잃는다. 재구독 시 Valkey 스냅샷이 채운다.
- **market-ingest는 하나다.** 죽으면 재시작될 때까지 시세 송신 · 중계가 멈춘다. market-service는 마지막 시세를 계속 보여 준다. 이중화는 필요해지면 다시 정한다.
- **HPA가 줄이면 그 파드의 WebSocket이 끊긴다.** 프론트가 다시 연결한다.
- **계약이 market-core에 모인다.** 두 앱 사이의 계약(Valkey 키 · 값 JSON · pub/sub 채널)은 market-core 클래스다. 두 앱 태그를 함께 올린다.
