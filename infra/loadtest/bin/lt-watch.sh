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

record() { # <파일> <kubectl 인자...>
  local file="$1"
  shift
  # API 서버는 watch를 30~60분 뒤에 끊는다(--min-request-timeout). 끊기면 다시 붙는다.
  # 다시 붙을 때 현재 목록이 ADDED로 한 번 더 찍힌다. 2026-10-22 T6에서는 31분 만에 기록이 멈췄다.
  # 바로 실패하면(터널 · 컨텍스트 없음) 2초마다 같은 오류를 쌓지 않게 대기를 30초까지 늘린다.
  (
    wait_s=2
    while :; do
      started=$SECONDS
      "$@" 2>&1 | stamp >>"$dir/$file"
      printf '%s (watch 끊김, %s초 뒤 다시 연결)\n' "$(TZ=Asia/Seoul date '+%F %T')" "$wait_s" >>"$dir/$file"
      sleep "$wait_s"
      if ((SECONDS - started - wait_s < 10)); then
        wait_s=$((wait_s * 2 > 30 ? 30 : wait_s * 2))
      else
        wait_s=2
      fi
    done
  ) &
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

# 반복 루프와 그 안의 kubectl까지 같은 프로세스 그룹을 모두 끝낸다(루프 서브셸만 죽이면 kubectl이 남는다).
trap 'trap - INT TERM; echo; echo "기록 끝: $dir"; kill 0 2>/dev/null' INT TERM
echo "기록 중: $dir (Ctrl+C로 끝)"
echo "부하 시작 시각(1)은 results/$id/meta.env의 START다."
wait
