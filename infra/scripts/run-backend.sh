#!/usr/bin/env bash
#
# 백엔드 서비스 5개(market-ingest 포함, §66)를 로컬 프로세스로 기동한다. (CLAUDE.md §32)
# 인프라는 먼저 'docker compose up -d' 로 띄워 두어야 한다.
#
#   ./infra/scripts/run-backend.sh
#   ./infra/scripts/health-check.sh
#   ./infra/scripts/stop-backend.sh

set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_env.sh"

mkdir -p "$RUN_DIR"

# 먼저 한 번만 컴파일한다. 여러 서비스의 bootRun을 동시에 띄우면 각자 :common 을 다시 컴파일하면서
# 같은 build 디렉터리를 서로 지우고 써서, 한쪽이 "bad class file"로 실패한다 (common이 바뀐 직후에 잘 난다).
echo "컴파일: backend (처음이면 JDK · 의존성을 내려받느라 오래 걸린다)"
(cd "$REPO_ROOT/backend" && ./gradlew classes --quiet --console=plain)

for i in "${!SERVICE_NAMES[@]}"; do
  name="${SERVICE_NAMES[$i]}"
  port="${SERVICE_PORTS[$i]}"

  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "건너뜀: $name (포트 $port 이미 사용 중)"
    continue
  fi

  echo "기동: $name -> :$port"
  (
    cd "$REPO_ROOT/backend"
    nohup ./gradlew ":${name}:bootRun" --quiet --console=plain \
      > "$RUN_DIR/${name}.log" 2>&1 &
    echo $! > "$RUN_DIR/${name}.pid"
  )
done

echo
echo "로그: $RUN_DIR/<service>.log"
echo "기동 확인: ./infra/scripts/health-check.sh"
