# trading-service 이미지. (CLAUDE.md §47 Phase 7, §54, ADR-0019)
#
# 빌드 컨텍스트는 backend/ 이다. 서비스마다 Dockerfile 이 따로라서 한 서비스만 다시 빌드 · 배포할 수 있다.
#
#   docker build -f backend/trading-service/Dockerfile -t wts/trading-service backend
#
# jar 는 이 안에서 Gradle 로 만든다. 로컬 빌드 산출물에 의존하지 않으므로 어느 PC 에서 빌드해도 같은 이미지가 나온다.
# 서비스들이 한 Gradle 멀티 프로젝트라 settings.gradle 이 전부 include 한다 → 소스를 통째로 복사한다.
# JVM 설정의 근거는 docs/decisions/0025-jvm-container-settings.md 이다.

# jar 는 아키텍처와 무관하다. buildx 멀티 아키텍처 빌드에서도 빌드 단계는 네이티브로 한 번만 돈다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :trading-service:bootJar -x test --no-daemon --console=plain
# fat jar 를 레이어별로 펼친다 (Spring Boot 권장 레이아웃). 중첩 jar 를 풀지 않아 시작이 빠르고,
# 의존성이 바뀌지 않으면 그 레이어는 다시 받지 않는다. bootJar 만 만들었으므로 *-SNAPSHOT.jar 는 하나다.
RUN cp trading-service/build/libs/*-SNAPSHOT.jar /app.jar \
    && java -Djarmode=tools -jar /app.jar extract --layers --destination /extracted

FROM eclipse-temurin:21-jre-alpine

ARG SERVICE=trading-service
LABEL org.opencontainers.image.title="wts-${SERVICE}"

# root 로 돌리지 않는다. 쿠버네티스 runAsNonRoot 는 이름이 아니라 숫자 UID 로만 검증할 수 있다.
RUN addgroup -S -g 10001 wts && adduser -S -u 10001 -G wts wts
WORKDIR /app

# 자주 바뀌지 않는 것부터 쌓는다. COPY 하나가 레이어 하나다. 결과는 /app/app.jar + /app/lib/*.jar 다.
COPY --from=build --chown=10001:10001 /extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /extracted/application/ ./

USER 10001

# 컨테이너 메모리 한도를 JVM 이 인식하게 한다 (§54 – Container Ready).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
# 시각은 UTC 로 기록한다 (§43).
ENV TZ=UTC

# 정지 신호를 JVM 이 직접 받아야 Graceful Shutdown 이 동작한다 (§54). exec 로 java 가 PID 1 이 된다.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
