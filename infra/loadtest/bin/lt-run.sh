#!/usr/bin/env bash
# k6 시나리오를 발생기 파드(k6-runner)에서 돌린다. 파드가 여러 개면 모두에서 동시에 돌리고,
# 부하(N · RATE · CONNS)와 시험 계정을 파드 수로 나눈다(RUNNERS / RUNNER_INDEX).
#
#   ./bin/lt-run.sh [--redo] <testid> <시나리오.js> [k6 옵션...]
#   ./bin/lt-run.sh T0-20261021-1 t0-smoke.js
#   ./bin/lt-run.sh T3-20261021-1 mixed.js -e N=100 -e PROFILE=steady
#   ./bin/lt-run.sh --redo T2-WSSCAN-20261020-1 t2-ws.js -e MODE=scan ...   # 같은 testid로 다시
#
# --redo: 예전 결과를 results/_old/<testid>-<시각>/로 옮기고(지우지 않음), 파드 안 예전 결과를 비운 뒤 다시 돈다.
#         Prometheus에서 예전 회차와 섞이지 않게 k6 지표에 attempt 태그를 붙인다(lt-export.py가 그 회차만 본다).
#
# testid는 회차-날짜-번호로 짓는다. Grafana · Prometheus에서 이 값(라벨 testid)으로 회차를 고른다.
# k6는 파드 안에서 nohup으로 돈다: 이 스크립트를 Ctrl+C로 끊어도 테스트는 계속된다(멈추려면 lt-stop.sh).
source "$(dirname "${BASH_SOURCE[0]}")/lt-env.sh"

redo=0
if [[ "${1:-}" == "--redo" ]]; then
  redo=1
  shift
fi
if [[ $# -lt 2 ]]; then
  sed -n '2,15p' "$0" >&2
  exit 2
fi
id="$1" scenario="$2"
shift 2
[[ -f "$LT_K6_DIR/scenarios/$scenario" ]] || { echo "없는 시나리오: $LT_K6_DIR/scenarios/$scenario" >&2; exit 2; }

# macOS 기본 bash 3.2에는 mapfile이 없다.
pods=()
while IFS= read -r p; do [[ -n "$p" ]] && pods+=("$p"); done < <(runner_pods)
if [[ ${#pods[@]} -eq 0 ]]; then
  echo "Running 상태 발생기 파드가 없다. 먼저: kubectl --context $LT_CHANNEL_CTX -n $LT_NS scale deploy/k6-runner --replicas=1" >&2
  exit 1
fi
runners=${#pods[@]}

# 이미 도는 k6가 있으면 시작하지 않는다(두 회차가 겹치면 둘 다 결과를 쓸 수 없다).
for pod in "${pods[@]}"; do
  if kc -n "$LT_NS" exec "$pod" -c "$LT_CONTAINER" -- pgrep -x k6 >/dev/null 2>&1; then
    echo "${pod}에서 k6가 아직 돌고 있다. 끝나기를 기다리거나 ./bin/lt-stop.sh 뒤에 다시 한다." >&2
    exit 1
  fi
done

out="$LT_RESULTS/$id"
attempt=""
pod_has=0
for pod in "${pods[@]}"; do
  kc -n "$LT_NS" exec "$pod" -c "$LT_CONTAINER" -- test -d "/work/results/${id}" 2>/dev/null && pod_has=1
done
if [[ -e "$out/meta.env" || $pod_has -eq 1 ]]; then
  if [[ $redo -eq 0 ]]; then
    echo "이미 있는 testid다: ${id}. 번호를 올려서 새로 하거나, 같은 이름으로 다시 하려면 --redo를 붙인다:" >&2
    echo "  $0 --redo ${id} ${scenario} $*" >&2
    exit 1
  fi
  attempt="$(date +%H%M%S)"
  if [[ -e "$out" ]]; then
    mkdir -p "$LT_RESULTS/_old"
    mv "$out" "$LT_RESULTS/_old/${id}-${attempt}"
    echo "예전 결과를 옮겼다: results/_old/${id}-${attempt}"
  fi
  for pod in "${pods[@]}"; do
    kc -n "$LT_NS" exec "$pod" -c "$LT_CONTAINER" -- rm -rf "/work/results/${id}"
  done
fi
mkdir -p "$out"

# 사용자 인자를 원격 sh에 안전하게 넘긴다.
extra=""
for a in "$@"; do extra+=" $(printf '%q' "$a")"; done

# 실행 게이트(계획서 §5.6): 승인 기록이 없으면 경고만 한다(로컬 연습 · smoke는 막지 않음).
if [[ -z "${LT_APPROVAL:-}" ]]; then
  echo "경고: LT_APPROVAL(승인 기록 링크 또는 ID)이 비어 있다. T1 이후 회차는 승인 후 export LT_APPROVAL=... 하고 실행한다(계획서 §5.7)." >&2
fi

# 감사 기록(계획서 §5.3): 무엇을 · 누가 · 어떤 이미지로 돌렸나. 토큰 · 비밀번호는 남기지 않는다.
image_id="$(kc -n "$LT_NS" get pod "${pods[0]}" -o jsonpath='{.status.containerStatuses[0].imageID}' 2>/dev/null || echo unknown)"
k6_version="$(kc -n "$LT_NS" exec "${pods[0]}" -c "$LT_CONTAINER" -- k6 version 2>/dev/null | head -1 || echo unknown)"
{
  echo "TESTID=$id"
  echo "SCENARIO=$scenario"
  echo "ARGS='$*'"
  echo "RUNNERS=$runners"
  echo "PODS='${pods[*]}'"
  echo "IMAGE_ID='$image_id'"
  echo "K6_VERSION='$k6_version'"
  # 커밋 안 된 수정 · 새 파일이 있으면 -dirty(그 커밋의 스크립트와 다르게 돌았다는 뜻)
  echo "GIT_COMMIT=$(git -C "$LT_HOME" rev-parse --short HEAD 2>/dev/null || echo unknown)$([ -z "$(git -C "$LT_HOME" status --porcelain -- k6 2>/dev/null)" ] || echo '-dirty')"
  echo "OPERATOR='$(git config user.name 2>/dev/null || echo unknown) / $(aws sts get-caller-identity --query Arn --output text 2>/dev/null || echo unknown)'"
  echo "APPROVAL='${LT_APPROVAL:-}'"
  echo "ATTEMPT=${attempt}"
  echo "START=$(date -u +%s)"
} >"$out/meta.env"

for i in "${!pods[@]}"; do
  pod="${pods[$i]}"
  echo "== $pod (runner $i/$runners): 스크립트 복사"
  kc -n "$LT_NS" exec "$pod" -c "$LT_CONTAINER" -- sh -c "rm -rf /work/k6 && mkdir -p /work/results/$id"
  kc -n "$LT_NS" cp "$LT_K6_DIR" "$pod:/work/k6" -c "$LT_CONTAINER"
  echo "== $pod: k6 시작"
  kc -n "$LT_NS" exec "$pod" -c "$LT_CONTAINER" -- sh -c "
    cd /work/k6 &&
    export K6_PROMETHEUS_RW_SERVER_URL='$LT_PROM_RW' &&
    export K6_PROMETHEUS_RW_TREND_STATS='p(95),p(99),avg,max' &&
    export K6_PROMETHEUS_RW_PUSH_INTERVAL=5s &&
    nohup sh -c 'k6 run --quiet --out experimental-prometheus-rw \
      --tag testid=$id --tag runner=$i ${attempt:+--tag attempt=$attempt} \
      --summary-export /work/results/$id/summary.json \
      -e BASE_URL=$LT_BASE_URL -e RUNNERS=$runners -e RUNNER_INDEX=$i \
      $extra scenarios/$scenario; echo \$? > /work/results/$id/exit_code' \
      > /work/results/$id/k6.log 2>&1 &
  "
done

cat <<EOF

시작했다: testid=${id}, 발생기 ${runners}개.
  로그 따라가기(Ctrl+C는 보기만 멈춘다):  kubectl --context $LT_CHANNEL_CTX -n $LT_NS exec ${pods[0]} -c $LT_CONTAINER -- tail -f /work/results/$id/k6.log
  Grafana에서 testid=$id 로 거른다.
  끝나면:  ./bin/lt-fetch.sh $id
  당장 멈추기:  ./bin/lt-stop.sh
EOF

if [[ "${LT_FOLLOW:-1}" == "1" ]]; then
  echo "--- ${pods[0]} 로그 (k6가 끝나면 자동으로 돌아온다) ---"
  # 로그 보기는 SSM 터널을 탄다. 터널이 끊겨도 k6는 파드에서 계속 돈다(nohup). 끊기면 터널이 돌아올 때까지
  # 30초마다 다시 붙고, 이어서 본다(이미 본 줄은 다시 보이지 않게 끝 20줄부터).
  from="+1"
  while :; do
    kc -n "$LT_NS" exec "${pods[0]}" -c "$LT_CONTAINER" -- sh -c "
      tail -n $from -f /work/results/$id/k6.log & t=\$!
      while [ ! -f /work/results/$id/exit_code ]; do sleep 3; done
      sleep 1; kill \$t" 2>/dev/null && break
    # 끝났는지(exit_code)는 파드에서 확인한다. 연결이 끊긴 것과 k6가 끝난 것을 구분한다.
    if kc -n "$LT_NS" exec "${pods[0]}" -c "$LT_CONTAINER" -- test -f "/work/results/${id}/exit_code" 2>/dev/null; then
      break
    fi
    echo "--- 로그 연결이 끊겼다(SSM 터널?). k6는 파드에서 계속 돈다. 30초 뒤 다시 붙는다. 터널이 끊겼으면 다른 터미널에서 ./bin/lt-tunnel.sh channel ---" >&2
    sleep 30
    from="20"
  done
  # 종료 시각은 Mac 시각이 아니라 파드에서 k6가 exit_code를 쓴 시각이다.
  end="$(kc -n "$LT_NS" exec "${pods[0]}" -c "$LT_CONTAINER" -- stat -c %Y "/work/results/${id}/exit_code" 2>/dev/null || true)"
  if [[ -n "$end" ]]; then
    echo "END=${end}" >>"$out/meta.env"
  fi
  echo "k6 종료. 다음: ./bin/lt-fetch.sh $id"
fi
