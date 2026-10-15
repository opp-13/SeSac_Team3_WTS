# trading-service 이미지. (CLAUDE.md §47 Phase 7, §54, ADR-0019)
#
# 빌드 컨텍스트는 backend/ 이다. 서비스마다 Dockerfile 이 따로라서 한 서비스만 다시 빌드 · 배포할 수 있다.
#
#   docker build -f backend/trading-service/Dockerfile -t wts/trading-service backend
#
# jar 는 이 안에서 Gradle 로 만든다. 로컬 빌드 산출물에 의존하지 않으므로 어느 PC 에서 빌드해도 같은 이미지가 나온다.
# 4 서비스가 한 Gradle 멀티 프로젝트라 settings.gradle 이 전부 include 한다 → 소스를 통째로 복사한다.

# jar 는 아키텍처와 무관하다. buildx 멀티 아키텍처 빌드에서도 빌드 단계는 네이티브로 한 번만 돈다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :trading-service:bootJar -x test --no-daemon --console=plain

FROM eclipse-temurin:21-jre-alpine

ARG SERVICE=trading-service
LABEL org.opencontainers.image.title="wts-${SERVICE}"

# root 로 돌리지 않는다. 쿠버네티스 runAsNonRoot 는 이름이 아니라 숫자 UID 로만 검증할 수 있다.
RUN addgroup -S -g 10001 wts && adduser -S -u 10001 -G wts wts
WORKDIR /app

# bootJar 만 만들었으므로 *-SNAPSHOT.jar 는 하나다 (plain.jar 는 생기지 않는다).
COPY --from=build --chown=10001:10001 /src/trading-service/build/libs/*-SNAPSHOT.jar app.jar

USER 10001

# 컨테이너 메모리 한도를 JVM 이 인식하게 한다 (§54 – Container Ready).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
# 시각은 UTC 로 기록한다 (§43).
ENV TZ=UTC

# 정지 신호를 JVM 이 직접 받아야 Graceful Shutdown 이 동작한다 (§54). exec 형식이라 java 가 PID 1 이다.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
