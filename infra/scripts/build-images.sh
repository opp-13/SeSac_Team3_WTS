#!/usr/bin/env bash
# 배포용 컨테이너 이미지를 만든다. (CLAUDE.md §47 Phase 7, ADR-0019)
#
#   ./infra/scripts/build-images.sh [태그]
#
# 서비스마다 backend/<서비스>/Dockerfile 이 있고, jar 는 이미지 안에서 Gradle 로 만든다.
# 로컬에 JDK · Gradle 빌드 산출물이 없어도 된다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TAG="${1:-local}"
SERVICES=(gateway-service market-service market-ingest trading-service user-service)

for service in "${SERVICES[@]}"; do
    echo "▸ wts/${service}:${TAG}"
    docker build -q \
        -f "$ROOT/backend/${service}/Dockerfile" \
        -t "wts/${service}:${TAG}" \
        "$ROOT/backend" > /dev/null
done

echo "▸ wts/frontend:${TAG}"
docker build -q -t "wts/frontend:${TAG}" "$ROOT/frontend" > /dev/null

echo
docker images --filter "reference=wts/*:${TAG}" --format '{{.Repository}}:{{.Tag}}\t{{.Size}}' | sort
