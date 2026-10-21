#!/usr/bin/env bash
# 정합성 검증(계획서 §7.12). corebank MySQL(wts_trading)에서 sql/consistency.sql을 돌린다.
# 모든 검사의 bad가 0이어야 한다. 부하 회차가 끝날 때마다 돌린다.
#   ./bin/lt-sql.sh [testid]     # testid를 주면 결과를 results/<testid>/consistency.txt에도 남긴다
# 필요: corebank 터널(./bin/lt-tunnel.sh corebank). DB는 평일 08:00~20:10에만 켜져 있다.
source "$(dirname "${BASH_SOURCE[0]}")/lt-env.sh"

rootpw="$(kcb -n mysql get secret mysql-cluster-secret -o jsonpath='{.data.rootPassword}' | base64 -d)"
run() {
  kcb -n mysql exec -i mysql-0 -c mysql -- env MYSQL_PWD="$rootpw" \
    mysql -h mysql.mysql.svc -P 3306 -uroot --table wts_trading <"$LT_HOME/sql/consistency.sql"
}

if [[ -n "${1:-}" ]]; then
  mkdir -p "$LT_RESULTS/$1"
  run | tee "$LT_RESULTS/$1/consistency.txt"
else
  run
fi
