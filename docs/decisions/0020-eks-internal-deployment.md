# ADR-0020: EKS 검증 배포 – tier 배치, 시험용 진입점, 막힌 지점

- 상태: 채택 (1단계). 2단계는 설계 전이다
- 날짜: 2026-10-14
- 관련: CLAUDE.md §62.4, §64. 갱신: ADR-0018 4항(배포 시점), ADR-0006. 참조: trader-gitops `docs/app-deploy-guide.md`, `docs/wts-deploy.md`,
  trader-terraform-sessac ADR-0041(인터넷 진입점) · ADR-0045(진입점 값 전달)
- 개정: 2026-10-14 같은 날, 1항의 "공개 진입점 없음"을 6항의 시험용 진입점으로 바꿨다

## 배경

trader-gitops의 두 EKS 클러스터(channel, corebank)에 Kafka(Strimzi)와 MySQL(InnoDBCluster)이 있다.
WTS 앱을 올려 앱이 그 Kafka · MySQL에 붙는지 확인한다.

## 결정

1. **검증 배포다.** ADR-0018 4항("Cognito 전에는 배포하지 않는다")은 이 배포에 **적용하지 않는다.** 팀이 이 배포에서 Cognito를 쓰지 않기로 했다.
   Mock Login은 이메일만 알면 그 사용자로 들어갈 수 있으므로, 이 예외의 조건은 **시험 계정만 쓰고 실사용자 · 실제 개인정보를 받지 않는 것**이다.
   진입점은 6항을 본다. 처음에는 "공개 진입점 없음(port-forward만)"이었으나 같은 날 6항으로 바꿨다.
2. **배치.**

   | 서비스 | tier | 이유 |
   |---|---|---|
   | frontend · gateway-service · user-service · market-service | channel | 사용자 접점 · 시세 · 사용자 |
   | trading-service (주문 · 체결 · 원장) | corebank | 계정계 |

   Valkey는 각 tier에 하나씩 둔다(클러스터에 없었다). 재생성 가능한 캐시라 영속성은 없다 (§18).
3. **Spring 설정은 환경변수로 덮는다.** 코드를 바꾸지 않는다. 클러스터 Kafka는 TLS 9093 + SCRAM-SHA-512만 열려 있어
   `SPRING_KAFKA_SECURITY_PROTOCOL=SASL_SSL` 등으로 접속 설정을 넣는다. JDBC는 `SPRING_DATASOURCE_URL`로 덮어 `sslMode=REQUIRED`로 붙는다.
4. **market-service · trading-service는 replica 1이다.** market은 Mock 시세 생성기 · KIS 연결이 둘이 되면 가격이 갈린다 (§56.4, Phase 10).
   trading은 엔진 다중 인스턴스 검증(Phase 8-3) 전이다.
5. **배포 정의는 서비스마다 plain 매니페스트다.** trader-gitops `applications/<tier>/wts/<서비스>/manifests/`에
   Deployment · Service를 그대로 쓰고(ServiceAccount 없음, API 토큰 마운트 끔), 이미지 경로만 ApplicationSet의 `kustomize.images`로 넣는다
   (계정 번호 · prefix는 GitOps Bridge annotation에서 온다). 공용 Helm 차트는 쓰지 않는다.
   컨테이너 세부 설정이 서비스마다 따로 바뀔 수 있어, 공용 차트에 조건 분기가 쌓이는 것보다 파일을 나누는 편이 낫다.
   대가: 공통 설정을 바꾸면 서비스마다 고치고, gitops의 `chart-inventory.sh check`가 이 매니페스트를 검사하지 않는다.
6. **인터넷 진입점은 기존 Terraform 진입점에 frontend만 붙인다 (시험용).**
   경로는 CloudFront(+WAF, KR) → VPC origin → internal ALB(Terraform 소유, trader-terraform-sessac ADR-0041) → frontend다.
   - frontend 파드는 TargetGroupBinding으로 대상 그룹에 등록한다. ALB를 새로 만들지 않는다.
     LBC의 Gateway API · Ingress는 기존 ALB를 쓸 수 없고, CloudFront origin이 그 ALB에 묶여 있다.
   - 대상 그룹 ARN · SG는 GitOps Bridge annotation으로 받는다(ADR-0045, Git에 계정 번호 없음).
   - 노출은 frontend 하나다. nginx가 `/api/` · `/ws/`를 gateway로 넘긴다.
   - CloudFront 뒤에서는 요청이 http로 gateway에 닿는다. 그래서 gateway의 `CORS_ALLOWED_ORIGINS`에 CloudFront 도메인(https)을 넣는다.
     넣지 않으면 로그인 POST와 WebSocket 업그레이드가 403이다(로컬 재현으로 확인).
   - 시험 계정만 쓴다. 실사용자는 ADR-0041 · 0012의 컴플라이언스 승인과 실제 인증이 생긴 뒤다.

7. **비밀값은 Secrets Manager에 두고 앱 pod가 파일로 마운트한다**(trader-terraform-sessac ADR-0047, 2026-10-15).
   - 방식: Secrets Store CSI Driver + AWS provider(EKS add-on)가 앱별 Pod Identity로 자기 시크릿만 `/mnt/secrets`에 마운트한다.
   - 앱이 읽는 법: Spring이 `SPRING_CONFIG_IMPORT=configtree:/mnt/secrets/`로 파일 이름을 속성으로 읽는다. 그래서 `${MYSQL_PASSWORD}` 등 기존 placeholder가 그대로 풀리고, **앱 코드는 바뀌지 않는다**(로컬 검증).
   - 손으로 만들던 `wts-db` · `wts-auth` Secret은 없어진다. MySQL root와 Kafka 접속 Secret은 Kubernetes Secret으로 남는다(operator · Strimzi가 쓰는 값).

## 막힌 지점 (이번 범위 밖)

channel → corebank는 internal ALB 443(HTTPS) 하나만 열려 있고, corebank → channel은 막혀 있다. Kafka(TCP)는 tier를 넘지 못한다.

| # | 문제 | 필요한 것 |
|---|---|---|
| 1 | gateway → trading 프록시 | corebank internal ALB + **ACM 인증서**. → trader-terraform-sessac ADR-0046(`trading.internal.cloudnetwork.kr`)으로 풀었다 |
| 2 | trading이 체결 판정에 쓰는 시세 | `market.price.updated`(Kafka)와 최신가(Valkey)가 모두 channel에만 있다. → **ADR-0021(CLAUDE.md §65)**: channel market-service가 서명한 HTTPS로 밀고 corebank trading-service가 받아 Valkey · Kafka에 다시 낸다 |
| 3 | Phase 8-2 설계(§57.2)의 market ← trading `order.accepted` 수요 신호 | corebank → channel이 막혀 있어 그대로는 불가능하다. channel이 끌어오는(pull) 방식으로 바꿔야 한다 |

그래서 **1단계 결과는 이렇다.**
- channel: frontend · gateway · user · market이 끝까지 동작한다 (로그인, 시세, 차트, 관심종목).
- corebank: trading-service가 뜨고 corebank의 MySQL · Kafka · Valkey에 붙는다. 시세가 없어 주문은 `MARKET_PRICE_UNAVAILABLE`로 거절되고,
  gateway에서는 아직 닿지 않는다 (gateway의 `TRADING_SERVICE_URI`는 비워 둔다).

2단계(인증서 · ALB · 시세 중계 · 수요 신호 방향)는 CLAUDE.md에 상세 설계 섹션을 먼저 덧붙인 뒤 구현한다 (§63).
