#!/usr/bin/env bash
# 시험 계정(lt-…@loadtest.local) 데이터 정리(계획서 §5.2). 시험 계정이 아닌 행은 건드리지 않는다.
#
#   ./bin/lt-cleanup.sh            # 미리보기(기본): 지울 행 수만 출력
#   ./bin/lt-cleanup.sh --apply    # 실제 삭제. 확인 문구를 입력해야 진행한다
#
# 순서: channel wts_user.users에서 시험 계정 id를 모은다 → corebank wts_trading에서 그 사용자들의 계좌를 찾아
#       executions → ledger_entries → orders → positions → virtual_accounts 순서로 지운다(FK 없음, V1~V3)
#       → channel watchlists → users. outbox_events는 이미 발행된 이벤트 기록이라 남긴다.
# 정합성 검사(lt-sql.sh)를 먼저 돌려 결과를 남긴 뒤에 지운다. 필요: channel · corebank 터널. DB는 평일 08:00~20:10.
source "$(dirname "${BASH_SOURCE[0]}")/lt-env.sh"

apply=0
[[ "${1:-}" == "--apply" ]] && apply=1
like="${EMAIL_PREFIX:-lt}-%@loadtest.local"

sqlc() { # channel: <db> <sql>
  local pw
  pw="$(kc -n mysql get secret mysql-cluster-secret -o jsonpath='{.data.rootPassword}' | base64 -d)"
  # SQL은 stdin으로 넘긴다. 시험 계정 수백 개의 id가 들어가 -e 인자로는 한도(128KB)를 넘는다(2026-10-21, 800개).
  kc -n mysql exec -i mysql-0 -c mysql -- env MYSQL_PWD="$pw" mysql -h mysql.mysql.svc -uroot -N -B "$1" <<<"$2"
}
sqlb() { # corebank: <db> <sql>
  local pw
  pw="$(kcb -n mysql get secret mysql-cluster-secret -o jsonpath='{.data.rootPassword}' | base64 -d)"
  kcb -n mysql exec -i mysql-0 -c mysql -- env MYSQL_PWD="$pw" mysql -h mysql.mysql.svc -uroot -N -B "$1" <<<"$2"
}

ids="$(sqlc wts_user "SELECT CONCAT('''', id, '''') FROM users WHERE email LIKE '$like'" | paste -sd, -)"
if [[ -z "$ids" ]]; then
  echo "시험 계정이 없다($like)."
  exit 0
fi
echo "시험 계정 수: $(echo "$ids" | tr ',' '\n' | wc -l | tr -d ' ')"

acc="SELECT id FROM virtual_accounts WHERE user_id IN ($ids)"
sqlb wts_trading "
SELECT 'virtual_accounts', COUNT(*) FROM virtual_accounts WHERE user_id IN ($ids)
UNION ALL SELECT 'orders', COUNT(*) FROM orders WHERE account_id IN ($acc)
UNION ALL SELECT 'executions', COUNT(*) FROM executions WHERE account_id IN ($acc)
UNION ALL SELECT 'ledger_entries', COUNT(*) FROM ledger_entries WHERE account_id IN ($acc)
UNION ALL SELECT 'positions', COUNT(*) FROM positions WHERE account_id IN ($acc)"
sqlc wts_user "SELECT 'watchlists', COUNT(*) FROM watchlists WHERE user_id IN ($ids)"

if [[ $apply -eq 0 ]]; then
  echo
  echo "미리보기만 했다. 지우려면: $0 --apply"
  exit 0
fi

read -r -p "위 행을 지운다. 계속하려면 DELETE 를 입력: " answer
[[ "$answer" == "DELETE" ]] || { echo "취소"; exit 1; }

# 계좌 id를 먼저 고정한다(지우는 도중 서브쿼리 결과가 바뀌지 않게).
accounts="$(sqlb wts_trading "$acc" | paste -sd, -)"
if [[ -n "$accounts" ]]; then
  sqlb wts_trading "
START TRANSACTION;
DELETE FROM executions     WHERE account_id IN ($accounts);
DELETE FROM ledger_entries WHERE account_id IN ($accounts);
DELETE FROM orders         WHERE account_id IN ($accounts);
DELETE FROM positions      WHERE account_id IN ($accounts);
DELETE FROM virtual_accounts WHERE id IN ($accounts);
COMMIT;"
fi
sqlc wts_user "
START TRANSACTION;
DELETE FROM watchlists WHERE user_id IN ($ids);
DELETE FROM users WHERE id IN ($ids);
COMMIT;"
echo "삭제 완료. 남은 시험 계정: $(sqlc wts_user "SELECT COUNT(*) FROM users WHERE email LIKE '$like'")"
