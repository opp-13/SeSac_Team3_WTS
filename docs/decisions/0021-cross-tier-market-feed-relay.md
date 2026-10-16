# ADR-0021: tier 간 시세 중계 – channel이 서명한 HTTPS로 밀고, corebank가 받아 다시 낸다

- 상태: 제안됨. EKS에서 corebank 주문이 실제 시세로 체결되면 채택으로 바꾼다
- 날짜: 2026-10-15
- 관련: CLAUDE.md §65, §64.4. ADR-0020(막힌 지점 2번을 푼다), ADR-0008(trading은 Valkey에서 시세를 읽는다, 유지),
  ADR-0010(Outbox), ADR-0011(중복은 상태 머신이 막는다). trader-terraform-sessac ADR-0046(develop internal ALB) · ADR-0047(Secrets Manager)

## 배경

EKS 검증 배포(ADR-0020)에서 market-service는 channel, trading-service는 corebank에 있다.
- **trading이 시세를 읽는 곳:** 최신 시세는 자기 tier의 Valkey(ADR-0008), 시세 이벤트는 자기 tier의 Kafka다.
- **시세가 있는 곳:** channel에만 있다.
- **네트워크:**
  - channel → corebank는 develop internal ALB 443 하나뿐이다.
  - corebank → channel은 막혀 있다.
  - Kafka는 tier를 넘지 못한다.
  - corebank에는 인터넷이 없다.
- **결과:** 2026-10-15 확인. channel Valkey에는 KIS 시세 5종목이 있고 corebank Valkey는 비어 있다. 모든 주문이 `MARKET_PRICE_UNAVAILABLE`로 거절된다.

## 조사 – 다른 곳은 어떻게 하나

| 출처 | 방식 | 시사점 |
|---|---|---|
| 거래 시스템의 ticker plant | 대외 구간에서 시세를 한 번 받아 정규화하고, 내부 거래 시스템(OMS · 매칭 · 리스크)에 분배한다 | 시세 수신과 거래는 다른 구역이고, 시세는 한 방향으로 흐른다 |
| 금융권 망분리 · 데이터 다이오드 | 안쪽 망은 바깥으로 세션을 열지 않는다. 바깥쪽이 정해진 통로로 밀어 넣는다. 국내 규제는 내 · 외부망 실시간 연계를 예외로 다루고 통제를 요구한다 | 덜 신뢰하는 쪽이 연결을 연다. 통로는 좁고 하나다 |
| 토스증권(AWS 블로그) | 해외 시세를 미국 리전 EKS가 MSK에서 받아 Transit Gateway로 국내 DC에 보낸다. DC는 보안 점검 뒤 Data Processor가 받아 내부 브로커로 분배한다. 시세는 최신성, 체결은 정합성을 우선한다 | 보내는 쪽이 밀고, 받는 쪽의 수신 게이트웨이가 내부로 다시 낸다 |
| Confluent Cluster Linking | 목적지가 출발지에 닿을 수 없을 때 쓰는 source-initiated link가 있다 | 같은 상황의 표준 해법이 "출발지가 연다"이다. 상용 기능이라 Strimzi에는 없다 |
| Kafka MirrorMaker 2 | 기본은 목적지 쪽에서 당겨 온다 | 우리는 반대 방향이 막혀 있다 |
| Goldman Sachs(MSK + PrivateLink) | 계정 간 Kafka를 소비자 VPC에서만 시작되는 단방향 연결로 붙인다 | 경계 간 연결은 한 방향 · 좁은 통로 |
| Strimzi HTTP Bridge | HTTP로 produce한다. HTTP 클라이언트 인증은 직접 하지 않는다 | 443에는 맞지만 인증을 따로 붙여야 한다 |

## 결정

**channel의 market-service가 시세를 서명한 HTTPS로 corebank에 밀고, corebank의 trading-service가 받아 자기 tier의 Valkey와 Kafka에 다시 낸다.** 상세는 CLAUDE.md §65.

1. **보내는 쪽(channel):** market-service 안의 feed relay가 한다.
   - `market.price.updated`를 별도 컨슈머 그룹으로 묶어서 소비한다.
   - 종목별 최신값만 `POST /internal/market/prices`로 보낸다.
   - 실패하면 버리고 재시도하지 않는다. 시세는 다시 온다.
2. **받는 쪽(corebank):** trading-service 안의 market feed 수신이 한다.
   - 서명 · 시각을 검증한다.
   - 오래됐거나 더 새롭지 않은 시세는 버린다.
   - Valkey `market:price:{symbol}`(market과 같은 형식)과 Kafka `market.price.updated`(받은 봉투 그대로, key = 종목코드)에 낸다.
   - trading의 기존 읽기 경로는 바뀌지 않는다.
3. **인증:** HMAC-SHA256 서명 헤더(`X-Wts-Signature: t=…,v1=…`)를 쓴다.
   - 키는 Secrets Manager `wts/<tier>/market-feed`에 두고, 두 앱만 Pod Identity로 읽는다(trader-terraform-sessac ADR-0047).
   - 서명에 시각을 넣어 오래된 요청의 재전송을 막는다.
4. **경로:** 이미 있는 develop ALB 443에 `/internal/market/prices` 규칙 하나만 더한다.
   - gateway는 `/internal/**`를 라우팅하지 않는다.
   - 공개 API(§24)가 아니므로 OpenAPI 명세에서 뺀다.
5. **Kafka 직접 발행이다.** Outbox(ADR-0010)를 거치지 않는다.
   - Outbox는 거래 DB 변경과 이벤트를 함께 커밋하려는 장치다. 중계하는 시세는 trading의 DB 상태가 아니고, 잃어도 다음 시세가 덮는다.
6. **설정으로 켠다.** 기본은 꺼짐이다. 로컬과 한 클러스터 배포는 두 서비스가 같은 Valkey · Kafka를 쓰므로 필요 없다.
7. **방향 규칙:** tier 경계를 넘는 연결은 언제나 channel이 연다. Phase 8-2의 수요 신호도 channel이 corebank에서 당겨 오는 방식으로 설계한다.

## 검토한 대안

| 대안 | 버린 이유 |
|---|---|
| MirrorMaker 2를 channel에서 push → corebank Kafka | corebank Kafka를 tier 밖에 노출해야 한다(브로커별 주소, 443 TLS passthrough, NLB 또는 ingress-nginx). 인프라가 크고 노출 범위가 넓다 |
| channel relay → Strimzi HTTP Bridge(corebank) | 수신 코드는 없어진다. 하지만 HTTP 인증이 없다. ALB 헤더 조건으로 막으면 비밀값이 Terraform state에 들어간다(trader-terraform-sessac ADR-0022). Valkey 투영도 따로 만들어야 한다 |
| trading이 market을 동기 호출 | 방향이 막혀 있고, §6.2가 금지한다 |
| corebank에서 KIS를 직접 받는다 | corebank에 인터넷이 없다. KIS 연결은 앱키당 하나다(§56.4) |

## 결과

- **좋은 점**
  - 네트워크 변경이 ALB 경로 규칙 하나뿐이다.
  - trading의 시세 읽기 · 체결 코드가 그대로다.
  - Phase 8 체결 엔진(§58)도 corebank Kafka에서 같은 토픽을 받는다.
- **지연:** 묶음(최대 300ms) + HTTPS 왕복만큼 늘어난다. max-age(5초) 안이다.
- **야간:** develop 노드가 꺼지면(21시 이후) ALB가 503을 준다. relay는 로그만 남기고 계속 돈다.
- **위험:** 키가 새면 channel 안의 파드가 가짜 시세를 넣어 체결을 일으킬 수 있다.
  - 키는 두 앱만 읽고, 교체 절차를 런북에 둔다.
  - X-User-Id 위조(trader-terraform-sessac ADR-0046)와 같은 계열의 위험이다.

## 장애 기록 – 2026-10-16 첫 장중, 중계 0건

**증상**
- channel Valkey 시세는 갱신되는데 corebank Valkey가 비어 있었다.
- 중계 실패 로그도 수신 쪽 로그도 없었다.

**원인**
- **시각이 숫자로 발행된다.** market-service는 `value-serializer: JsonSerializer`를 설정으로 지정한다. 그래서 Kafka가 기본 생성자로 직렬화기를 만들고, Spring Boot 매퍼가 아니라 Spring Kafka 기본 매퍼(`JacksonUtils.enhancedObjectMapper()`)를 쓴다. 이 매퍼는 `WRITE_DATES_AS_TIMESTAMPS`를 끄지 않아, 이벤트의 시각이 숫자(epoch 초.나노)로 나간다.
- **relay는 ISO 문자열만 읽었다.** `Instant.parse(문자열)`로 읽어서 모든 이벤트를 형식 오류로 버렸다.
- **버린 사실이 보이지 않았다.** 형식 오류는 debug 로그만 남겼다.

**왜 테스트가 못 잡았나**
- 테스트 이벤트를 ISO 문자열로 손으로 만들었다. 운영 형식과 달랐다.
- 기존 소비자는 Jackson으로 `Instant`에 매핑하므로 두 형식을 모두 읽는다. 그래서 이 차이가 드러난 적이 없었다.

**조치**
- **읽기:** 두 서비스가 시각을 Jackson으로 읽는다. 숫자와 ISO를 모두 받는다.
- **계약 테스트:** 운영과 같은 `JsonSerializer`로 만든 이벤트를 relay에 넣는 테스트를 추가했다. 통합 테스트의 이벤트도 발행자와 같은 매퍼로 만든다.
- **로그:** relay와 수신 쪽 모두 버린 이유별 건수를 1분마다 INFO로 요약한다.
- **교훈:** 서비스 간 계약을 시험할 때는 손으로 쓴 JSON이 아니라 실제 발행 경로의 직렬화기로 만든다.

## 출처

- [토스증권의 미국 Option 시세 실시간 제공 사례 (AWS 기술 블로그)](https://aws.amazon.com/ko/blogs/tech/toss-securities-nasdaq-options-realtime-pipeline/)
- [Confluent – Hybrid Cluster Linking (source-initiated links)](https://docs.confluent.io/platform/current/multi-dc-deployments/cluster-linking/hybrid-cp.md)
- [Red Hat – Demystifying Kafka MirrorMaker 2](https://developers.redhat.com/articles/2023/11/13/demystifying-kafka-mirrormaker-2-use-cases-and-architecture)
- [Canonical – MirrorMaker 2.0 overview](https://canonical.com/data/kafka/docs/latest/explanation/mirrormaker2-0/)
- [How Goldman Sachs builds cross-account connectivity to Amazon MSK with AWS PrivateLink](https://aws.amazon.com/blogs/big-data/how-goldman-sachs-builds-cross-account-connectivity-to-their-amazon-msk-clusters-with-aws-privatelink)
- [Strimzi HTTP Bridge](https://strimzi.io/docs/bridge/latest/)
- [Exegy – Centralized ticker plant](https://www.exegy.com/centralized-ticker-plant/)
- [OPSWAT – Data diodes in financial IT](https://www.opswat.com/blog/securing-financial-it-infrastructure-with-data-diodes-opswat)
- [금감원 금융전산 망분리 예외기준 (2015)](https://www.sateconomy.co.kr/news/view/179587125176871)
- [금융위·금감원 – DMZ 웹서버를 통한 내·외부망 실시간 중계 해석](https://casenote.kr/%EA%B8%88%EC%9C%B5%EC%9C%84%EC%9B%90%ED%9A%8C%C2%B7%EA%B8%88%EC%9C%B5%EA%B0%90%EB%8F%85%EC%9B%90/67f44c4071)
