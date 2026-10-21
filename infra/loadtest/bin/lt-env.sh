#!/usr/bin/env bash
# 부하 테스트 스크립트 공통 변수. 다른 lt-*.sh가 source 한다. 값은 환경변수로 덮어쓴다.
# 런북: trader-terraform-sessac docs/experiment/wts-load-test-runbook.md
set -euo pipefail

LT_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export LT_HOME
export LT_K6_DIR="$LT_HOME/k6"
export LT_RESULTS="${LT_RESULTS:-$LT_HOME/results}"

export AWS_REGION="${AWS_REGION:-ap-northeast-2}"

# 클러스터 (trader-terraform-sessac modules/network-plan)
export LT_CHANNEL_CLUSTER="${LT_CHANNEL_CLUSTER:-eks-terraform-ingress-channel}"
export LT_COREBANK_CLUSTER="${LT_COREBANK_CLUSTER:-eks-terraform-develop-corebank}"
# lt-tunnel.sh가 만드는 kubectl context 이름과 Mac 쪽 로컬 포트
export LT_CHANNEL_CTX="${LT_CHANNEL_CTX:-lt-channel}"
export LT_COREBANK_CTX="${LT_COREBANK_CTX:-lt-corebank}"
export LT_CHANNEL_API_PORT="${LT_CHANNEL_API_PORT:-8443}"
export LT_COREBANK_API_PORT="${LT_COREBANK_API_PORT:-8444}"
# 배스천 instance-id. 모르면: ./bin/lt-tunnel.sh find
export LT_CHANNEL_BASTION="${LT_CHANNEL_BASTION:-}"
export LT_COREBANK_BASTION="${LT_COREBANK_BASTION:-}"

# 발생기 (trader-gitops applications/channel/loadtest)
export LT_NS="${LT_NS:-loadtest}"
export LT_RUNNER_SELECTOR="${LT_RUNNER_SELECTOR:-app.kubernetes.io/name=k6-runner}"
export LT_CONTAINER="${LT_CONTAINER:-k6}"

# 발생기 파드 안에서 보는 주소(구성 A: 클러스터 내부, 계획서 §5.1)
export LT_BASE_URL="${LT_BASE_URL:-http://gateway-service.wts.svc:8080}"
export LT_PROM_RW="${LT_PROM_RW:-http://ls-observe-prometheus.monitoring.svc:9090/api/v1/write}"

# Mac에서 포트포워드한 Prometheus (lt-tunnel.sh 안내 참고)
export LT_PROM="${LT_PROM:-http://localhost:9090}"
export LT_PROM_COREBANK="${LT_PROM_COREBANK:-http://localhost:9091}"

kc() { kubectl --context "$LT_CHANNEL_CTX" "$@"; }
kcb() { kubectl --context "$LT_COREBANK_CTX" "$@"; }

runner_pods() {
  kc -n "$LT_NS" get pods -l "$LT_RUNNER_SELECTOR" --field-selector=status.phase=Running \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | sort
}
