# ADR-0025: JVM 컨테이너 설정 – JVM 옵션은 그대로, 레이어 jar, probe timeout

- 상태: 제안됨. 부하 테스트(trader-gitops `docs/wts-load-test-plan.md` T3 · T8)에서 OOMKilled 0이면 채택으로 바꾼다
- 갱신(2026-10-23): 초안의 힙 60% · G1 · direct memory 상한 · 프로세서 2개 고정은 넣지 않기로 했다. 충분히 이해하지 못한 JVM 메모리 · GC 설정은 건드리지 않는다. 아래 "배경"은 알려진 사항으로 남긴다
- 날짜: 2026-10-23
- 관련: CLAUDE.md §54, §64.3. 갱신: ADR-0019(서비스마다 Dockerfile)

## 배경

5개 앱(gateway · user · market-service · market-ingest · trading)의 컨테이너는 메모리 requests = limits 768Mi이고, CPU는 requests 250m만 있다(limit 없음). JVM 옵션은 `-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport`뿐이었다.

이 설정에서 생기던 문제:

1. **GC가 SerialGC였다.** 메모리가 1,792MB 미만이면 JVM ergonomics가 SerialGC를 고른다([Microsoft – JVM 기본 ergonomics](https://learn.microsoft.com/en-us/azure/developer/java/containers/overview)). SerialGC는 단일 스레드로 전체를 멈춘다. 요청 · WebSocket 지연에 불리하다.
2. **non-heap 자리가 좁았다.** 힙 75%(약 576MB)를 빼면 약 190MB가 남는다. 이 안에 메타스페이스, 코드 캐시, 스레드 스택(Tomcat 최대 200개), GC 자료구조, direct buffer가 다 들어가야 한다. 힙을 한도에 가깝게 잡으면 non-heap이 한도를 넘겨 OOMKilled 된다([AWS – JVM best practices for Java containers](https://aws.amazon.com/blogs/containers/jvm-memory-cpu-and-classpath-best-practices-for-java-containers-on-aws/)). Netty처럼 off-heap을 많이 쓰면 60~70%를 권한다(같은 글).
3. **direct memory 상한이 사실상 없었다.** `MaxDirectMemorySize` 기본값은 최대 힙과 같다. gateway(Reactor Netty)와 Redis 클라이언트(Lettuce, Netty)가 쓰는 direct buffer가 힙 밖에서 커질 수 있다([Netty direct memory와 OOMKilled 사례](https://www.proofpoint.com/us/blog/engineering-insights/direct-memory-and-container-oomkilled-errors)).
4. **프로세서 수가 노드에 따라 달랐다.** JDK 19부터 CPU shares(requests)를 프로세서 수 계산에 쓰지 않는다([JDK-8281181](https://mail.openjdk.org/pipermail/hotspot-dev/2022-March/058191.html)). CPU limit이 없으면 노드 vCPU 수(app NodePool c7g/m7g large · xlarge = 2~4)가 그대로 보인다. 그래서 GC · JIT · Netty 스레드 수가 노드 종류에 따라 달라진다. AWS 글은 할당량에 맞춰 `-XX:ActiveProcessorCount`를 고정하라고 권한다.
5. **liveness probe timeout이 기본값 1초였다.** 바쁜 노드나 GC 정지 때 건강한 파드가 재시작될 수 있다. AWS 글은 probe를 최악의 GC 정지 시간에 맞추라고 권한다.

측정(로컬, gateway, 기동 직후): non-heap 사용 약 67MB, direct buffer 16MB, 스레드 18~20개. fat jar 1.29s → 펼친 jar 1.02s(로컬 Mac 기준이라 절대값은 의미가 없고 상대 차이만 본다). JPA · Kafka를 쓰는 market · trading은 non-heap이 더 크다(부하 테스트에서 잰다).

## 결정

### 1. JVM 옵션은 바꾸지 않는다

- Dockerfile `JAVA_OPTS`는 운영과 같은 `-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport`로 둔다. 결과는 SerialGC, 힙 약 576MB다.
- 힙 비율, GC, direct memory, 프로세서 수는 바꾸지 않는다.
- 필요해지면 Deployment의 env `JAVA_OPTS`로 통째로 덮어쓸 수 있다. 그때는 이 ADR을 다시 연다.

운영 실측. EKS 7일 최대(2026-10-16~23, 부하 테스트 포함), 이미지 `8c8381d`, 단위 MiB. 판단 근거로 기록만 한다:

| 서비스 | 힙 사용 | non-heap (메타스페이스) | direct | working set | SerialGC 최대 정지 |
|---|---|---|---|---|---|
| gateway | 170 | 117 (59) | 20 | 406 | 775ms |
| market-service | 248 | 194 (116) | 9 | 635 | 543ms |
| market-ingest | 99 | 224 (130) | 8 | 480 | 322ms |
| trading | 118 | 236 (134) | 5 | 476 | 569ms |
| user-service | 55 | 168 (94) | 0 | 325 | 204ms |

### 2. 레이어로 펼친 jar

- 빌드 단계에서 `java -Djarmode=tools -jar app.jar extract --layers`로 jar를 펼친다.
- 런타임 이미지에는 `dependencies → spring-boot-loader → snapshot-dependencies → application` 순으로 COPY한다([Spring Boot – Dockerfiles](https://docs.spring.io/spring-boot/3.5/reference/packaging/container-images/dockerfiles.html)).
  - 중첩 jar를 실행 중에 풀지 않아 시작이 빠르다.
  - 의존성 레이어는 코드만 바뀐 배포에서 다시 받지 않는다.
- 실행 명령은 그대로 `exec java $JAVA_OPTS -jar app.jar`다(PID 1, graceful shutdown).

### 3. probe timeout

- trader-gitops의 5개 Deployment에서 startup · readiness · liveness 모두 `timeoutSeconds: 3`(기본 1)으로 둔다.

## 검토하고 하지 않은 것

- **초안의 JVM 옵션(`MaxRAMPercentage=60 InitialRAMPercentage=60 MaxDirectMemorySize=128m ActiveProcessorCount=2 UseG1GC ExitOnOutOfMemoryError`):** 넣지 않았다(2026-10-23). 운영 non-heap이 로컬 측정(67MB)보다 훨씬 커서(최대 236MB) 60% · G1이면 trading이 768Mi에 닿을 수 있다는 계산이 나왔다. 그리고 확신 없이 메모리 · GC를 바꾸지 않기로 했다.

- **CPU limit = requests(Guaranteed QoS):** AWS 글은 JVM에 limit = request를 권한다. 그러나 requests가 250m인 지금 limit을 걸면 기동(JIT)과 GC가 CFS 스로틀링으로 크게 느려진다. CPU limit 없이 두고, 부하 테스트 실측으로 requests를 올리는 것을 먼저 한다(부하 테스트 계획 §7).
- **CDS / AOT 캐시 학습 실행:** 시작을 더 줄일 수 있다(Spring Boot 같은 문서). 다만 학습 실행이 컨텍스트를 띄우며 DB(Flyway · JPA 검증)에 붙으려 해, 이미지 빌드 중에 돌리기 어렵다. 레이어 jar로 먼저 바꾸고 따로 검토한다.
- **메모리 한도 상향(1Gi 등):** 비용과 노드 밀도에 걸린다. 부하 테스트 soak(T8)에서 non-heap · RSS를 보고 정한다.

## 결과

- JVM 동작(힙 · GC)은 지금과 같다.
- 이미지 레이어가 4개 늘어난다. 같은 의존성이면 push · pull이 빨라지고, 중첩 jar를 풀지 않아 시작이 조금 빨라진다.
- probe가 GC 정지나 바쁜 노드 때문에 건강한 파드를 재시작하지 않는다(timeout 3초, 실측 최대 정지 775ms).
- 다시 볼 조건
  - OOMKilled가 나거나 working set이 한도의 85%(653MiB)를 넘음(지금 market-service 635MiB)
  - 부하 테스트에서 GC 정지 비율 > 5%, 또는 GC 정지가 WebSocket 시세 공백 기준(1.5초)에 닿음
  - 힙 사용률이 꾸준히 85%를 넘음
