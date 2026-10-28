# ADR-0019: 백엔드 이미지는 서비스별 Dockerfile + 멀티스테이지로 만든다

- 상태: 채택
- 날짜: 2026-10-14
- 관련: CLAUDE.md §47 Phase 7, §54, §64. 대체: `docs/architecture/phase7-ci.md`의 "Dockerfile 하나 + 이미지 안에서 Gradle 금지" 결정
- 갱신: ADR-0025 — jar를 레이어로 펼쳐 넣는다(JVM 옵션은 그대로)

## 배경

Phase 7은 `backend/Dockerfile` 하나에 `SERVICE` 빌드 인자를 받아 네 서비스를 만들었다.
jar는 CI의 `build` 단계(`backend:assemble`)가 미리 만들고, 이미지는 그것을 COPY만 했다.
"같은 코드를 두 번 빌드하지 않는다"가 이유였다.

이제 서비스를 두 EKS 클러스터에 나눠 올린다 (ADR-0020). channel에는 frontend · gateway · user · market,
corebank에는 trading만 간다. 서비스마다 따로 빌드 · 태그 · push할 수 있어야 한다.

## 결정

1. **서비스마다 `backend/<서비스>/Dockerfile`을 둔다.** 빌드 컨텍스트는 `backend/`이다.
2. **멀티스테이지다.** 빌드 단계(`eclipse-temurin:21-jdk`)가 `./gradlew :<서비스>:bootJar`로 jar를 만들고,
   실행 단계(`21-jre-alpine`)에는 jar만 복사한다. 이미지가 로컬 빌드 산출물에 의존하지 않는다.
3. **빌드 단계는 `--platform=$BUILDPLATFORM`이다.** jar는 아키텍처와 무관해서 arm64 + amd64 멀티 아키텍처
   빌드에서도 Gradle은 한 번만 돈다. 노드 아키텍처가 NodePool 설정에 달려 있어 둘 다 필요하다.
4. **실행 사용자는 숫자 UID 10001이다.** 쿠버네티스 `runAsNonRoot`는 이름(`wts`)으로는 검증하지 못한다.
5. **CI의 `build` 단계와 `backend:assemble`을 없앤다.** `docker` 단계가 서비스별 job(`parallel: matrix`)이 된다.
6. **레지스트리 push는 CI가 하지 않는다.** `infra/scripts/push-images.sh <tier> <태그>`를 사람이 실행한다
   (ECR 리포가 IMMUTABLE이고 CI용 push 권한이 아직 없다. trader-gitops `docs/app-deploy-guide.md` 12절).

## 대가

- `settings.gradle`이 네 서비스를 모두 include해서 소스를 통째로 COPY한다. 한 서비스를 고쳐도
  다른 서비스의 빌드 레이어 캐시가 무효화된다. BuildKit의 Gradle 캐시 마운트(`--mount=type=cache`)로 완화하지만
  이미지 빌드가 Phase 7보다 느리다.
- CI에서 Gradle이 두 번 돈다(`backend:unit-test` · `integration-test`, 그리고 서비스별 이미지 빌드). 이미지 빌드는 `-x test`다.
- 서비스 디렉터리마다 거의 같은 Dockerfile이 생긴다. 서비스 이름만 다르다. 한 곳을 고치면 모든 서비스를 같이 고친다.
- 2026-10-16: `market-ingest`가 추가되어 다섯이 됐다(ADR-0022). 공유 라이브러리 `market-core`는 이미지가 없다.

## 되돌리는 방법

공통 Dockerfile + `SERVICE` 인자 방식으로 돌아가려면 이 ADR을 대체하는 새 ADR을 남긴다.
