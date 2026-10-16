#!/usr/bin/env bash
# tier 에 배포할 이미지를 멀티 아키텍처로 빌드해 ECR 에 올린다. (ADR-0019, ADR-0020)
#
#   ./infra/scripts/push-images.sh <channel|corebank> <태그> [--dry-run]
#
#   channel   frontend · gateway-service · user-service · market-service · market-ingest  → eks-terraform-ingress
#   corebank  trading-service                                             → eks-terraform-develop
#
# 사용자가 인터넷이 되는 PC 에서 실행한다 (trader-gitops docs/app-deploy-guide.md 6절, docs/wts-deploy.md).
# 필요: aws CLI(ECR 권한), docker buildx.
#
# 태그는 ECR 에서 IMMUTABLE 이다. latest 는 쓸 수 없고, 같은 태그를 다시 올리면 실패한다.
# 코드를 바꿨으면 새 태그(git SHA 권장)를 쓴다.
#
# 환경변수(선택): AWS_ACCOUNT_ID(기본 511999299465), AWS_REGION(기본 ap-northeast-2)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ACCOUNT="${AWS_ACCOUNT_ID:-511999299465}"
REGION="${AWS_REGION:-ap-northeast-2}"
REGISTRY="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"

usage() {
    echo "사용법: $0 <channel|corebank> <태그> [--dry-run]" >&2
    exit 2
}

[[ $# -ge 2 ]] || usage
TIER="$1"
TAG="$2"
DRY_RUN=false
[[ "${3:-}" == "--dry-run" ]] && DRY_RUN=true

case "$TIER" in
    channel)
        NAME_PREFIX="eks-terraform-ingress"
        BACKENDS=(gateway-service user-service market-service market-ingest)
        FRONTEND=true
        ;;
    corebank)
        NAME_PREFIX="eks-terraform-develop"
        BACKENDS=(trading-service)
        FRONTEND=false
        ;;
    *) usage ;;
esac

if [[ "$TAG" == "latest" || -z "$TAG" ]]; then
    echo "오류: 태그는 latest 가 될 수 없다. ECR 이 IMMUTABLE 이라 불변 태그(git SHA)를 쓴다." >&2
    exit 2
fi

run() {
    if $DRY_RUN; then
        echo "[dry-run] $*"
    else
        "$@"
    fi
}

image() { echo "${REGISTRY}/${NAME_PREFIX}/apps/wts/$1:${TAG}"; }

echo "▸ tier=${TIER}  registry=${REGISTRY}/${NAME_PREFIX}  tag=${TAG}"

if ! $DRY_RUN; then
    aws ecr get-login-password --region "$REGION" \
        | docker login --username AWS --password-stdin "$REGISTRY"
fi

for service in "${BACKENDS[@]}"; do
    echo "▸ $(image "$service")"
    run docker buildx build \
        --platform linux/arm64,linux/amd64 \
        -f "$ROOT/backend/${service}/Dockerfile" \
        -t "$(image "$service")" \
        --push \
        "$ROOT/backend"
done

if $FRONTEND; then
    echo "▸ $(image frontend)"
    run docker buildx build \
        --platform linux/arm64,linux/amd64 \
        -t "$(image frontend)" \
        --push \
        "$ROOT/frontend"
fi

echo
echo "확인: docker buildx imagetools inspect <이미지>  (linux/arm64, linux/amd64 둘 다 보여야 한다)"
