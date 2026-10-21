#!/usr/bin/env bash
# 오토스케일 타임라인 기록(계획서 §7.8 T6 · §7.9 T7의 1~6 시각). 부하를 걸기 전에 켜고, 끝나면 Ctrl+C.
#   ./bin/lt-watch.sh <testid>
# results/<testid>/timeline/ 에 줄마다 시각(KST)이 붙은 로그를 남긴다:
#   hpa.log        HPA 현재/목표 레플리카, 지표값        (2 지표가 목표를 넘음, 3 desiredReplicas 변경)
#   pods.log       wts 파드 생성 · Pending · Ready       (4 파드 생성 → 노드 배정, 5 Ready)
#   nodeclaims.log Karpenter NodeClaim 생성 · Ready      (4 노드 증설)
#   events.log     wts 이벤트(스케일 · 스케줄 실패 등)
#   corebank-*.log trading (corebank 터널이 열려 있을 때)
source "$(dirname "${BASH_SOURCE[0]}")/lt-env.sh"

id="${1:?사용법: $0 <testid>}"
dir="$LT_RESULTS/$id/timeline"
mkdir -p "$dir"

stamp() { while IFS= read -r line; do printf '%s %s\n' "$(TZ=Asia/Seoul date '+%F %T')" "$line"; done; }

pids=()
record() { # <파일> <kubectl 인자...>
  local file="$1"
  shift
  ("$@" 2>&1 | stamp >>"$dir/$file") &
  pids+=($!)
}

record hpa.log kubectl --context "$LT_CHANNEL_CTX" -n wts get hpa -w --output-watch-events
record pods.log kubectl --context "$LT_CHANNEL_CTX" -n wts get pods -o wide -w --output-watch-events
record nodeclaims.log kubectl --context "$LT_CHANNEL_CTX" get nodeclaims -w --output-watch-events
record events.log kubectl --context "$LT_CHANNEL_CTX" -n wts get events -w
if kubectl --context "$LT_COREBANK_CTX" version --request-timeout=3s >/dev/null 2>&1; then
  record corebank-hpa.log kubectl --context "$LT_COREBANK_CTX" -n wts get hpa -w --output-watch-events
  record corebank-pods.log kubectl --context "$LT_COREBANK_CTX" -n wts get pods -o wide -w --output-watch-events
else
  echo "corebank 터널이 없어 trading은 기록하지 않는다(./bin/lt-tunnel.sh corebank)."
fi

trap 'kill "${pids[@]}" 2>/dev/null; echo; echo "기록 끝: $dir"' INT TERM
echo "기록 중: $dir (Ctrl+C로 끝)"
echo "부하 시작 시각(1)은 results/$id/meta.env의 START다."
wait
