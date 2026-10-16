#!/usr/bin/env bash
#
# Phase 0 완료조건 검증: 인프라와 백엔드 서비스(5개, market-ingest 포함)가 모두 정상인지 확인한다. (CLAUDE.md §47, §66)
# 서비스가 뜰 때까지 기다렸다가 결과를 출력하고, 하나라도 실패하면 1로 종료한다.

set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_env.sh"

TIMEOUT_SECONDS="${HEALTH_TIMEOUT:-180}"
failed=0

echo "── 인프라 (docker compose) ────────────────────────────"
docker compose -f "$REPO_ROOT/docker-compose.yml" ps \
  --format 'table {{.Service}}\t{{.Status}}' 2>/dev/null || failed=1

echo
echo "── Kafka 브로커 ───────────────────────────────────────"
if docker exec wts-kafka /opt/kafka/bin/kafka-broker-api-versions.sh \
     --bootstrap-server localhost:9092 >/dev/null 2>&1; then
  echo "  kafka: 응답 정상"
else
  echo "  kafka: 응답 없음"
  failed=1
fi

echo
echo "── 백엔드 서비스 health ───────────────────────────────"
for i in "${!SERVICE_NAMES[@]}"; do
  name="${SERVICE_NAMES[$i]}"
  port="${SERVICE_PORTS[$i]}"
  url="http://localhost:${port}/actuator/health"

  deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  code=000
  while [[ $(date +%s) -lt $deadline ]]; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url" || echo 000)"
    [[ "$code" == "200" ]] && break
    sleep 2
  done

  status="$(curl -s --max-time 3 "$url" | python3 -c \
    'import json,sys; print(json.load(sys.stdin).get("status","?"))' 2>/dev/null || echo '?')"

  if [[ "$code" == "200" && "$status" == "UP" ]]; then
    printf '  %-16s %-5s HTTP %s  %s\n' "$name" ":$port" "$code" "$status"
  else
    printf '  %-16s %-5s HTTP %s  %s   <-- 실패\n' "$name" ":$port" "$code" "$status"
    # 무엇이 DOWN인지 바로 보여준다.
    curl -s --max-time 3 "$url" | python3 -c '
import json, sys
try:
    body = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for comp, value in (body.get("components") or {}).items():
    if value.get("status") != "UP":
        print(f"      {comp}: {value.get(\"status\")}")
' 2>/dev/null
    failed=1
  fi
done

echo
if [[ $failed -eq 0 ]]; then
  echo "Phase 0 완료조건 충족: 모든 서비스 health 200 / UP"
else
  echo "실패한 항목이 있다. 로그 확인: $RUN_DIR/<service>.log"
fi
exit $failed
