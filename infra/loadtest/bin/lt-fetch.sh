#!/usr/bin/env bash
# 발생기 파드에서 결과(k6.log, summary.json, exit_code)를 results/<testid>/runner-<i>/로 가져온다.
#   ./bin/lt-fetch.sh <testid>
# 종료 코드: 0 = 모든 threshold 통과, 99 = threshold 실패(합격 기준 미달 또는 중단 조건), 그 밖 = 실행 문제(로그 확인).
source "$(dirname "${BASH_SOURCE[0]}")/lt-env.sh"

id="${1:?사용법: $0 <testid>}"
out="$LT_RESULTS/$id"
mkdir -p "$out"

i=0
running=0
for pod in $(runner_pods); do
  dst="$out/runner-$i"
  mkdir -p "$dst"
  kc -n "$LT_NS" cp "$pod:/work/results/$id" "$dst" -c "$LT_CONTAINER" >/dev/null
  if [[ -f "$dst/exit_code" ]]; then
    echo "== runner-${i} (${pod}): 종료 코드 $(cat "$dst/exit_code")"
  else
    echo "== runner-${i} (${pod}): 아직 실행 중"
    running=1
  fi
  i=$((i + 1))
done

if [[ $running -eq 1 ]]; then
  echo
  echo "아직 끝나지 않았다. 끝난 뒤에 다시 실행한다(종료 시각은 그때 기록한다):"
  echo "  kubectl --context ${LT_CHANNEL_CTX} -n ${LT_NS} exec deploy/k6-runner -- tail -f /work/results/${id}/k6.log"
  exit 0
fi
# 종료 시각: k6가 파드 안에서 exit_code를 쓴 시각. kubectl cp로 받은 파일의 수정 시각은 "받은 시각"이라 쓰면 안 된다
# (T2-WSSCAN-20261020-1에서 종료 시각이 15분 늦게 잡혀 테스트 뒤 구간이 섞였다).
if ! grep -q '^END=' "$out/meta.env" 2>/dev/null; then
  first="$(runner_pods | head -1)"
  end="$(kc -n "$LT_NS" exec "$first" -c "$LT_CONTAINER" -- stat -c %Y "/work/results/${id}/exit_code" 2>/dev/null || true)"
  if [[ -z "$end" ]]; then
    end="$(date -u +%s)"
    echo "경고: 파드에서 종료 시각을 읽지 못해 지금 시각을 썼다. lt-export.py에 --end를 직접 준다." >&2
  fi
  echo "END=${end}" >>"$out/meta.env"
fi

echo
echo "--- runner-0 요약(마지막 60줄) ---"
tail -n 60 "$out/runner-0/k6.log" 2>/dev/null || true
echo
echo "다음: ./bin/lt-export.py $id   (Prometheus 포트포워드가 열려 있어야 한다)"
