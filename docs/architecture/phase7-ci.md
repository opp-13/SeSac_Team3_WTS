# Phase 7 – CI

> CLAUDE.md §47 완료조건: GitLab CI · Frontend Build · Backend Test · Integration Test · Docker Build

§37의 파이프라인을 `.gitlab-ci.yml` 하나로 옮기고, 그 마지막 단계가 만들 수 있도록
컨테이너 이미지를 정의했다.

## 파이프라인

```
Push / Merge Request
      │
      ▼
 lint          frontend:lint            oxlint
      │
      ▼
 test          frontend:build           tsc -b && vite build
               backend:unit-test        ./gradlew test          152개
      │
      ▼
 integration   backend:integration-test ./gradlew integrationTest  74개
      │
      ▼
 docker        docker:backend           서비스 4개를 병렬로 (matrix)
               docker:frontend          이미지 1개
```

> **갱신 (ADR-0019):** `build` 단계(`backend:assemble`)를 없앴다. jar는 서비스마다 이미지 안에서 만든다.
> §38이 제시한 스테이지 이름 중 `build`는 쓰지 않는다.

## 단위 테스트와 통합 테스트를 나눈 방법

§37은 "Backend Unit Test" 다음에 "Integration Test"를 둔다. 나누는 이유는 **속도**다.
통합 테스트는 Testcontainers로 MySQL·Kafka·Valkey를 띄운다. 컴파일 오류나 도메인 로직
버그는 컨테이너를 띄우기 전에 걸러져야 한다.

**기준을 클래스 이름으로 잡지 않았다.** `ConfigurableInitialCashTest`처럼 이름에
`IntegrationTest`가 없으면서 컨테이너가 필요한 테스트가 있다. 이름 규칙은 언젠가 어긋난다.

각 서비스의 `IntegrationTestBase`에 `@Tag("integration")`을 붙였다.
JUnit의 `@Tag`는 상속되므로 이 클래스를 상속하는 테스트가 전부 포함된다.
**컨테이너가 필요한 테스트는 전부 이 클래스를 상속한다** — 그것이 실제 기준이다.

```gradle
tasks.named('test')           { useJUnitPlatform { excludeTags 'integration' } }
tasks.register('integrationTest', Test) { useJUnitPlatform { includeTags 'integration' } }
tasks.named('check')          { dependsOn tasks.named('integrationTest') }
```

마지막 줄이 중요하다. **로컬에서 `./gradlew build`는 여전히 전부 실행한다.**
나눈 것은 CI를 위해서지 로컬에서 통합 테스트를 건너뛰려는 게 아니다.

| | 개수 | 서비스별 |
|---|---|---|
| `./gradlew test` | 152 | common 19 · gateway 13 · market 63 · trading 55 · user 2 |
| `./gradlew integrationTest` | 74 | market 9 · trading 50 · user 15 |
| `./gradlew build` | 226 | 둘 다 |

## 컨테이너 이미지

### 백엔드 — 서비스마다 `backend/<서비스>/Dockerfile` (ADR-0019)

```bash
docker build -f backend/gateway-service/Dockerfile -t wts/gateway-service backend
```

> **갱신 (ADR-0019):** 처음에는 `backend/Dockerfile` 하나에 `SERVICE` 빌드 인자를 받았고,
> 이미지 안에서 Gradle을 돌리지 않았다(CI가 만든 jar를 담기만 했다). 지금은 반대다.
> 서비스마다 Dockerfile이 따로 있는 멀티스테이지이고, jar를 이미지 안에서 Gradle로 만든다.
> 서비스를 channel · corebank 두 클러스터에 나눠 배포하게 되어, 서비스별로 빌드 · 태그 · 배포할 수 있어야 하기 때문이다.

| 결정 | 이유 |
|---|---|
| 멀티스테이지 (`eclipse-temurin:21-jdk` → `21-jre-alpine`) | 실행 이미지에는 JRE만 남는다. 컴파일러와 Gradle 캐시는 빌드 단계에만 있다 |
| 빌드 단계는 `--platform=$BUILDPLATFORM` | jar는 아키텍처와 무관하다. arm64 + amd64 멀티 아키텍처 빌드에서도 Gradle은 한 번만 돈다 |
| 비-root, **숫자 UID 10001** | 쿠버네티스 `runAsNonRoot`는 사용자 이름이 아니라 숫자 UID로만 검증한다 |
| `-XX:MaxRAMPercentage=75` | 컨테이너 메모리 한도를 JVM이 인식한다 (§54) |
| `exec java …` | JVM이 PID 1이 되어 정지 신호를 직접 받는다. Graceful Shutdown이 동작한다 (§54) |
| `TZ=UTC` | 저장은 UTC다 (§43) |

`settings.gradle`이 서비스 4개를 모두 include하므로 소스는 통째로 복사한다(`backend/.dockerignore`가 빌드 산출물 · 테스트를 뺀다).
그래서 한 서비스를 고쳐도 다른 서비스의 Gradle 레이어 캐시가 무효화된다. 빌드 시간보다 단순함을 택했다.

### 프론트엔드 — `frontend/Dockerfile` + `nginx.conf`

node로 빌드하고 nginx가 서빙한다. nginx가 하는 일은 개발 중 Vite 개발 서버가 하던 것과 같다.

```nginx
location /     { try_files $uri $uri/ /index.html; }   # SPA 라우팅 (§26)
location /api/ { proxy_pass http://gateway; }
location /ws/  { proxy_pass http://gateway; ... Upgrade 헤더 ... }   # 실시간 시세 (§25)
```

`try_files`가 없으면 `/wts/005930`으로 직접 들어오거나 새로고침할 때마다 404가 난다.
라우팅을 붙인 Phase 5에서 생긴 요구사항이다.

이미지 빌드도 CI와 **같은 명령**(`npm run build`)을 쓴다. 이미지에서만 타입 검사를
건너뛰면 "CI는 통과했는데 이미지는 다른 결과"가 생긴다.

## 컨테이너로 전체 실행 (§32)

```bash
./infra/scripts/build-images.sh
docker compose -f docker-compose.yml -f docker-compose.app.yml up -d
open http://localhost:5173
```

평소 개발은 `docker-compose.yml`(인프라만) + 로컬 프로세스가 빠르다.
이 조합은 **"배포하면 실제로 도는가"를 확인하는 용도**이고, CI가 만드는 것과 같은 이미지를 쓴다.

컨테이너 안에서는 포트를 고정하고(8080~8083) 외부 노출만 `.env`를 따른다.
서비스끼리는 컨테이너 이름으로 찾는다 — `mysql`, `kafka:19092`, `valkey`,
`http://market-service:8081`.

검증 결과: 로그인 → 시장가 매수 → 체결 → 포트폴리오까지 컨테이너 스택에서 동작하고,
`MARKET_PROVIDER=kis` 도 그대로 전달되어 실제 시세가 들어온다.

## GitLab 러너에 필요한 것

**통합 테스트와 이미지 빌드는 Docker-in-Docker를 쓴다.** 러너가 `privileged` 모드여야 한다.

```toml
# config.toml
[[runners]]
  executor = "docker"
  [runners.docker]
    privileged = true
```

Testcontainers는 dind 안에서 돌기 때문에 컨테이너 주소가 `localhost`가 아니다.
`TESTCONTAINERS_HOST_OVERRIDE=docker` 로 알려준다.

## 캐시

| job | 캐시 키 | 대상 |
|---|---|---|
| 프론트엔드 | `package-lock.json` | `frontend/node_modules` |
| 백엔드 | `gradle-wrapper.properties` + `libs.versions.toml` | `.gradle/wrapper`, `.gradle/caches` |

잠금 파일이 바뀔 때만 캐시가 무효화된다. `GRADLE_USER_HOME`을 작업 디렉터리 안으로
옮긴 것도 이 때문이다 — 밖에 있으면 job 사이에서 넘길 수 없다.

## 아직 저장소가 아니다

이 프로젝트는 **아직 git 저장소가 아니다.** `.gitlab-ci.yml`을 넣어도 push할 곳이 없으면
아무것도 돌지 않는다.

```bash
git init
git add .
git status          # .env 가 목록에 없는지 반드시 확인한다 (§33)
git commit -m "..."
git remote add origin <GitLab 저장소>
git push -u origin main
```

`.gitignore`가 `.env`를 제외하고 `.env.example`만 올리도록 되어 있다.
**첫 커밋 전에 `git status`로 한 번 눈으로 확인하는 것을 권한다.**
한 번 올라간 자격증명은 이력에서 지우기 어렵다.

## Phase 7에서 하지 않은 것

| 항목 | 이유 |
|---|---|
| 이미지 레지스트리 푸시 | §38이 "MVP 완료 후"로 둔 항목 |
| SonarQube | 같음 |
| GitOps 저장소 갱신 · Argo CD | 같음 |
| Kubernetes 배포 | §37이 "아직 필수가 아니다"라고 명시 |
| 통합 테스트 병렬 실행 | 74개가 30초대다. 느려지면 그때 |
