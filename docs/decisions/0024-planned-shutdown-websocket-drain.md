# ADR-0024: 계획된 종료에는 DRAIN으로 알리고 클라이언트가 새 연결을 먼저 맺는다

- 상태: 제안됨. EKS에서 브라우저 WebSocket을 연 채 market-service와 gateway를 각각 `rollout restart`했을 때 PRICE가 끊기지 않고 연결 상태 표시가 바뀌지 않으면 채택으로 바꾼다
- 날짜: 2026-10-19
- 관련: CLAUDE.md §68, §66.6, §25. 동작 설명: [docs/architecture/scale-in-without-gap.md](../architecture/scale-in-without-gap.md). 함께: trader-terraform-sessac ADR-0050(`/ws/*`를 gateway로 바로), trader-gitops gateway TargetGroupBinding

## 배경

- 파드가 내려갈 때(HPA 축소, 롤링 배포, Karpenter consolidation, 노드 drain) 그 파드를 지나던 브라우저 WebSocket은 끊긴다.
- §66.6까지는 끊긴 뒤에 다시 붙었다.
  - preStop으로 새 연결은 다른 파드로 보내고, 1001로 닫고, 지터를 두고 재연결했다.
  - 그래도 닫힘 → 대기 → 핸드셰이크 → 구독 → 스냅샷 사이에 0.3~1초 공백이 남았다.
- 요구 사항: 미리 알 수 있는 종료에서는 0.1초도 끊기지 않아야 한다.
- 연결은 브라우저 → CloudFront → ALB → frontend(nginx) → gateway → market-service로 이어진다. 내려가는 파드가 어느 구간이냐에 따라 할 수 있는 일이 다르다.

## 결정

1. **끊기 전에 알린다.**
   - 내려가는 쪽이 `{"type":"DRAIN"}`을 보낸다.
   - 클라이언트는 그 연결을 쥔 채 새 연결을 맺고 같은 구독을 보낸다. 새 연결이 `SUBSCRIBED`를 받으면 옛 연결을 1000으로 닫는다(make-before-break).
   - 새 연결이 다른 인스턴스로 가는 근거는 preStop(10s)이다. 그동안 엔드포인트 · ALB 대상에서 이미 빠져 있다.
2. **겹친 시세는 timestamp로 거른다.** 두 연결이 겹치는 동안 같은 시세가 순서 없이 두 번 올 수 있다. 화면 상태(`marketStore`)는 더 새로운 값만 반영한다. 시세는 "최신 상태"라 이것으로 충분하다.
3. **market-service:** `WebSocketShutdown`이 DRAIN을 보낸다 → 세션이 0이 되거나 `drain-timeout`(5s)이 될 때까지 기다린다 → 남은 세션은 1001로 닫는다.
4. **gateway:**
   - Spring Cloud Gateway의 WebSocket 프록시는 메시지를 끼워 넣을 자리가 없다.
   - 그래서 핸드셰이크 서비스를 감싸(`DrainingWebSocketService`, `@Primary`, 원래 `webSocketService`에 위임) 브라우저 쪽 세션을 추적한다.
   - 그 세션의 송신 흐름에 알림 흐름을 합친다(`DrainableSession`). 내려갈 때는 DRAIN → 대기 → 1001 순서다.
   - 라우트 · 프록시 로직 자체는 Gateway 것을 그대로 쓴다.
5. **nginx는 WebSocket 경로에서 뺀다.**
   - nginx는 메시지를 끼울 수 없다. graceful 종료가 끝나면 남은 연결이 강제로 끊긴다.
   - ALB 리스너 규칙으로 `/ws/*`를 gateway 대상 그룹에 바로 보낸다(trader-terraform-sessac ADR-0050).
6. **유휴 연결 유지.**
   - ALB 유휴 제한(기본 60초) 때문에 시세가 흐르지 않는 화면의 연결이 끊기던 문제도 함께 막는다.
   - 클라이언트가 25초마다 `PING`을 보내고, market-service가 `PONG`으로 답한다. gateway는 그대로 전달한다.
7. **시간 예산:** preStop 10s + drain 5s + graceful shutdown 20s = 35s ≤ `terminationGracePeriodSeconds` 45s.

## 검토한 대안

- **클라이언트가 늘 연결 두 개를 유지(hot standby):** 계획되지 않은 장애까지 막는다. 하지만 서버 WebSocket 부하가 2배가 되고, 두 연결을 다른 파드에 붙이는 장치도 따로 필요하다. 이번 범위(계획된 종료)에는 과하다.
- **gateway의 WebSocket 라우트를 직접 만든 프록시로 바꾸기:** 세션은 직접 다룰 수 있다. 하지만 헤더 전달, 서브프로토콜, 닫힘 코드 전달 같은 Gateway의 검증된 동작을 다시 만들어야 한다. 핸드셰이크 서비스를 감싸는 쪽이 작다.
- **nginx에 그대로 두고 graceful 시간만 늘리기:** 끊기는 시점이 늦어질 뿐 결국 끊긴다.

## 결과와 제한

- 계획된 종료에서 브라우저가 받는 시세가 끊기지 않는다. 연결 상태 표시도 "연결됨" 그대로다.
- `drain-timeout` 안에 옮겨 가지 않은 클라이언트(예: 이 기능이 없는 옛 프론트 번들)는 1001을 받고 기존 방식대로 재연결한다.
- **범위 밖**
  - 계획되지 않은 장애(노드 장애, OOM, 크래시): 알릴 틈이 없다.
  - market-ingest 재시작: 연결은 살아 있어도 시세 수집이 멈춘다. KIS 연결이 앱키당 하나다(§63.2).
  - CloudFront · ALB 자체 교체.
- 적용 순서가 있다(CLAUDE.md §68.4). ALB 규칙은 gateway 대상 그룹이 healthy가 된 뒤에 켠다.
