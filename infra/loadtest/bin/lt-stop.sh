#!/usr/bin/env bash
# 중단 스위치: 모든 발생기 파드의 k6에 SIGINT를 보낸다(요약을 쓰고 끝난다).
# 10초 안에 안 멈추면 한 번 더 실행한다(두 번째는 즉시 종료).
#   ./bin/lt-stop.sh
source "$(dirname "${BASH_SOURCE[0]}")/lt-env.sh"

for pod in $(runner_pods); do
  echo "== $pod"
  kc -n "$LT_NS" exec "$pod" -c "$LT_CONTAINER" -- sh -c 'pkill -INT -x k6 && echo "SIGINT 보냄" || echo "도는 k6 없음"'
done
