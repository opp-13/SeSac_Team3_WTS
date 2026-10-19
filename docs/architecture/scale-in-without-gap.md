# 파드가 내려갈 때 시세가 끊기지 않는 이유 – gateway 축소를 예로

CLAUDE.md §66.6, §68, ADR-0024의 동작을 순서대로 풀어 쓴 문서다. 파드 하나가 내려갈 때 다음 두 가지를 설명한다.
- Kubernetes · AWS가 그 파드를 **ALB 대상과 Service 엔드포인트에서 어떻게 빼는지**
- 앱이 그 사이에 **무엇을 하는지**

예는 gateway-service다. market-service도 같은 순서다(DRAIN을 보내는 쪽만 다르다).

---

## 1. 한눈에 보기

| 시점 | 일어나는 일 | 그 파드를 지나던 브라우저 |
|---|---|---|
| 0~10초 (preStop) | ALB 대상과 Service 엔드포인트에서 빠진다. 새 연결은 다른 gateway 파드로 간다 | 계속 시세를 받는다 |
| 10초 (SIGTERM) | `GatewayWebSocketDrain`이 프록시 중인 연결마다 DRAIN을 끼워 넣는다 | DRAIN을 받고 새 연결을 맺는다. 이 연결은 다른 gateway 파드로 가고, 그 뒤 market-service까지 이어진다. 옛 연결은 계속 시세를 준다 |
| +수십 ms | 새 연결이 `SUBSCRIBED`를 받는다 | 옛 연결을 1000으로 닫는다. 공백 없음 |
| 최대 15초 | 옮겨 가지 않은 연결만 1001로 닫힌다 → graceful shutdown → 종료 | (DRAIN을 모르는 옛 번들만 해당) |

- **REST 요청도 끊기지 않는다.** 진행 중인 요청은 graceful shutdown 20초 안에 끝나고, 새 요청은 preStop 동안 이미 다른 파드로 간다.
- **ALB 규칙을 켜기 전에도 gateway 축소는 무중단이다.** Terraform `ws_to_gateway=true`(Terraform ADR-0050) 전에는 경로가 nginx → `gateway-service` Service라, 새 연결이 Service를 통해 다른 gateway 파드로 간다. 이 규칙은 nginx 파드가 줄어들 때를 위한 것이다.

---

## 2. 파드가 내려가기 시작하면 세 곳이 동시에 움직인다

HPA가 개수를 줄이거나, 배포가 옛 파드를 지우거나, Karpenter가 노드를 비우면 결국 **파드 삭제 요청** 하나가 API 서버에 들어간다. API 서버는 파드에 `deletionTimestamp`를 찍고 상태를 `Terminating`으로 바꾼다. 이 순간부터 서로 모르는 세 주체가 **각자 동시에** 움직인다.

```
                     파드 삭제 (deletionTimestamp)
                               │
       ┌───────────────────────┼─────────────────────────────┐
       ▼                       ▼                             ▼
 ① kubelet (그 노드)    ② EndpointSlice 컨트롤러     ③ AWS Load Balancer Controller
 preStop 실행           엔드포인트를                 (TargetGroupBinding)
 (sleep 10s)            ready=false,                 엔드포인트 변화를 보고
       │                terminating=true 로 바꿈     ALB에 DeregisterTargets
       │                       │                             │
       │                       ▼                             ▼
       │                ④ kube-proxy (모든 노드)     ALB가 대상을 draining 으로:
       │                Service 규칙에서 제외         새 연결 · 요청을 보내지 않음
       │                → 새 연결은 다른 파드로       기존 연결은 유지
       ▼                  (기존 연결은 conntrack로     (deregistration_delay 30s까지)
 10초 뒤 SIGTERM          계속 유지)
 → 앱 종료 시작
```

### ① kubelet: preStop → SIGTERM → (필요하면) SIGKILL

- 파드가 있는 노드의 kubelet이 컨테이너의 `preStop`을 먼저 실행한다. 우리는 `sleep 10s`다.
- preStop이 끝나야 컨테이너에 SIGTERM을 보낸다.
- `terminationGracePeriodSeconds`(45s)는 **preStop 시작부터** 센다. 45초가 지나도 프로세스가 살아 있으면 SIGKILL이다.

### ② EndpointSlice 컨트롤러: 엔드포인트를 "종료 중"으로

- `gateway-service` Service에 딸린 EndpointSlice에서 이 파드 항목의 조건을 `ready: false`, `terminating: true`로 바꾼다.
- 기준은 readiness probe 결과가 아니라 `deletionTimestamp`다. probe가 실패할 때까지 기다리지 않고 바로 바뀐다.

### ④ kube-proxy: Service로 오는 새 연결에서 제외

- 모든 노드의 kube-proxy가 EndpointSlice 변화를 받아 iptables(또는 IPVS) 규칙에서 이 파드를 뺀다.
- 그래서 `gateway-service:8080`으로 오는 **새 연결**은 남은 파드로만 간다. 예를 들면 nginx가 gateway로 여는 연결이다.
- **이미 맺어진 TCP 연결**은 커널 conntrack에 남아 그대로 이어진다. 브라우저의 기존 WebSocket이 끊기지 않는 이유가 이것이다.

### ③ AWS Load Balancer Controller: ALB 대상 해제

- trader-gitops의 `TargetGroupBinding`(gateway-service, Terraform ADR-0050)은 컨트롤러에게 이렇게 지시한다: "이 Service의 엔드포인트(파드 IP:8080)를 이 대상 그룹에 맞춰 두라".
- 컨트롤러는 ②의 변화를 보고 ALB에 `DeregisterTargets`를 호출한다.
- ALB는 그 대상을 **draining** 상태로 둔다.
  - 새 연결 · 새 요청은 보내지 않는다.
  - 기존 연결은 `deregistration_delay`(30s)까지 유지한다.

### 왜 preStop 10초가 필요한가

- ②·③·④는 비동기다. 반영까지 보통 1~2초, 컨트롤러 재조정과 ALB API 호출까지 합치면 수 초가 걸린다.
- preStop이 없으면 SIGTERM이 ②·③·④와 **동시에** 온다. 그러면 앱이 종료를 시작한 뒤에도 몇 초 동안 새 연결 · 요청이 계속 이 파드로 들어온다(502, 핸드셰이크 실패).
- preStop 10초 동안 앱은 평소처럼 일한다. 그사이 ALB와 Service가 이 파드를 새 연결 대상에서 뺀다. SIGTERM이 올 즈음에는 **새로 들어오는 것은 없고, 기존 연결만 남는다.**

---

## 3. SIGTERM 뒤 앱이 하는 일

Spring Boot는 SIGTERM을 받으면 컨텍스트를 닫는다. SmartLifecycle 빈은 phase가 높은 것부터 멈춘다.

| 순서 | phase | 무엇 | 소요 |
|---|---|---|---|
| 1 | 기본(`DEFAULT_PHASE`) | `GatewayWebSocketDrain.stop()`: 이 인스턴스를 지나는 브라우저 세션마다 `{"type":"DRAIN"}`을 끼워 넣는다 → 세션이 모두 떠나거나 `wts.gateway.websocket.drain-timeout`(5s)이 지날 때까지 기다린다 → 남은 세션은 1001로 닫는다 | 보통 수십~수백 ms, 최대 5s |
| 2 | `DEFAULT_PHASE - 1024` | 웹 서버 graceful shutdown: 새 연결을 받지 않고, 진행 중인 HTTP 요청이 끝나기를 기다린다(`timeout-per-shutdown-phase` 20s) | 남은 REST 요청만큼 |
| 3 | 그 아래 | 웹 서버 정지, 나머지 빈 정리 → 프로세스 종료 | 짧음 |

- 1번이 2번보다 먼저 멈추므로, graceful shutdown은 끝나지 않는 WebSocket을 기다리지 않는다.
- 시간 예산: preStop 10s + drain 5s + graceful 20s = 35s ≤ `terminationGracePeriodSeconds` 45s.

### 브라우저 쪽 (frontend `lib/marketSocket.ts`)

1. 주 연결에서 DRAIN을 받으면 **주 연결은 그대로 둔 채** 새 WebSocket을 연다(`migrate`).
   - 이 연결은 ②·③ 덕분에 다른 gateway 파드로 간다.
2. 새 연결이 열리면 같은 종목을 SUBSCRIBE한다.
3. 새 연결이 `SUBSCRIBED`를 받으면 그쪽을 주 연결로 올리고 옛 연결을 1000으로 닫는다(`promote`).
4. 겹치는 동안 같은 시세가 두 번 올 수 있다. `marketStore`는 timestamp가 더 새로운 값만 반영한다.
5. 헤더의 연결 상태는 내내 "연결됨"이다.

---

## 4. 설정이 있는 곳

| 무엇 | 값 | 파일 |
|---|---|---|
| preStop 대기 | 10s | trader-gitops `applications/channel/wts/<앱>/manifests/deployment.yaml` (`lifecycle.preStop.sleep`) |
| 종료 대기 전체 | 45s | 같은 파일 `terminationGracePeriodSeconds` |
| gateway DRAIN 대기 | 5s | `backend/gateway-service/src/main/resources/application.yml` `wts.gateway.websocket.drain-timeout` |
| market-service DRAIN 대기 | 5s | `backend/market-service/src/main/resources/application.yml` `market.websocket.drain-timeout` |
| graceful shutdown | 20s | 각 서비스 `application.yml` `spring.lifecycle.timeout-per-shutdown-phase` |
| ALB 대상 해제 대기 | 30s | trader-terraform-sessac `envs/ingress/network/alb.tf` `deregistration_delay` |
| `/ws/*` → gateway | `ws_to_gateway` | 같은 파일. 대상 그룹 healthy 확인 후 true |
| 상시 2개 · 한 번에 하나씩 | HPA `minReplicas: 2`, PDB `maxUnavailable: 1` | trader-gitops `<앱>/manifests/hpa.yaml`, `pdb.yaml` |

---

## 5. 직접 확인하기

롤링 재시작을 걸고, 다른 터미널에서 각 단계가 실제로 일어나는지 본다.

```bash
kubectl -n wts rollout restart deploy/gateway-service

# ② 엔드포인트: 내려가는 파드가 ready=false, terminating=true 로 바뀐다
kubectl -n wts get endpointslices -l kubernetes.io/service-name=gateway-service -o yaml \
  | grep -E "ip:|ready:|terminating:|- [0-9]"

# ③ ALB: 내려가는 파드 IP가 draining 이 된다 (ws_to_gateway 적용 후)
aws elbv2 describe-target-health \
  --target-group-arn $(aws ssm get-parameter --name /network/ingress/alb_gateway_target_group_arn \
                         --query Parameter.Value --output text) \
  --query 'TargetHealthDescriptions[].[Target.Id,TargetHealth.State]' --output table

# 앱: 옮겨 간 세션 수와 1001로 닫은 세션 수 (1001이 0이면 모두 끊김 없이 옮겨 갔다)
kubectl -n wts logs <내려간 gateway 파드> --previous | grep "종료: WebSocket"
```

브라우저 개발자 도구(Network → WS)에서는 이렇게 보인다.
- 옛 연결에 `DRAIN`이 온다.
- 새 연결이 생기고 `SUBSCRIBED`가 온다.
- 옛 연결이 1000으로 닫힌다.
- 그사이 PRICE가 끊기지 않는다.

---

## 6. 한계

- **10초 안에 반영되지 않으면:** ②·③이 10초 안에 반영되지 않으면, 새 연결이 다시 내려가는 파드로 갈 수 있다. 그 연결은 결국 1001로 닫히고 재연결하므로 짧은 공백이 생긴다. 로그의 "1001로 닫은 세션"이 0이 아니면 의심한다.
- **ALB 헬스체크와 Kubernetes Ready의 차이:** 새 파드가 Kubernetes에서 Ready여도 ALB 헬스체크를 통과하기 전에는 ALB가 트래픽을 보내지 않는다. 상시 2개와 PDB가 있어 남은 파드가 받아 주지만, 더 엄격하게 하려면 AWS Load Balancer Controller의 pod readiness gate(`elbv2.k8s.aws/pod-readiness-gate-inject` 네임스페이스 라벨)를 검토한다. 이를 켜면 ALB에서 healthy가 된 뒤에야 파드가 Ready가 된다. 지금은 쓰지 않는다.
- **범위 밖:** 계획되지 않은 종료(노드 장애, OOM, 크래시)는 preStop · DRAIN이 실행되지 않으므로 이 흐름 밖이다(ADR-0024).

---

## 7. 참고 문서 (공식)

**Kubernetes**
- [Pod Lifecycle – Termination of Pods](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-termination): 삭제 요청 → `Terminating` → preStop → SIGTERM → grace period → SIGKILL 순서. grace period는 preStop 시작부터 센다
- [Pods and Endpoint Termination Flow (튜토리얼)](https://kubernetes.io/docs/tutorials/services/pods-and-endpoint-termination-flow/): 파드가 종료되는 동안 엔드포인트가 `ready: false`, `terminating: true`로 바뀌는 모습을 직접 재현한다 (2장 ②)
- [EndpointSlices – Conditions](https://kubernetes.io/docs/concepts/services-networking/endpoint-slices/#conditions): `ready` · `serving` · `terminating` 조건의 뜻
- [Virtual IPs and Service Proxies](https://kubernetes.io/docs/reference/networking/virtual-ips/): kube-proxy가 EndpointSlice를 보고 Service 규칙을 만드는 방식 (2장 ④)
- [Container Lifecycle Hooks – Hook handler implementations](https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/#hook-handler-implementations): preStop과 `sleep` 핸들러
- [Disruptions – Pod disruption budgets](https://kubernetes.io/docs/concepts/workloads/pods/disruptions/#pod-disruption-budgets): 자발적 중단(drain · consolidation)과 PDB
- [Horizontal Pod Autoscaling – Configurable scaling behavior](https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/#configurable-scaling-behavior): `scaleDown.stabilizationWindowSeconds` · `policies`

**AWS**
- [AWS Load Balancer Controller – TargetGroupBinding](https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/guide/targetgroupbinding/targetgroupbinding/): Service 엔드포인트를 기존 대상 그룹에 등록 · 해제 (2장 ③)
- [AWS Load Balancer Controller – Pod readiness gate](https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/deploy/pod_readiness_gate/): ALB 헬스체크를 통과해야 파드가 Ready가 되게 한다 (6장)
- [ALB 대상 그룹 속성 – Deregistration delay](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-target-group-attributes.html#deregistration-delay): draining 상태에서 새 요청은 보내지 않고 기존 연결은 유지한다
- [ALB 속성 – Connection idle timeout](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-load-balancer-attributes.html#connection-idle-timeout): 기본 60초. 브라우저 PING(25초)의 근거
- [EKS Best Practices – Load Balancing](https://docs.aws.amazon.com/eks/latest/best-practices/load-balancing.html): 파드 종료와 대상 해제의 경합, preStop으로 시간을 버는 방법, readiness gate
- [Karpenter – Disruption](https://karpenter.sh/docs/concepts/disruption/): consolidation, `karpenter.sh/do-not-disrupt`, PDB 존중

**Spring**
- [Spring Boot – Graceful Shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html): `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase` (3장)
