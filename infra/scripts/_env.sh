#!/usr/bin/env bash
# 저장소 루트의 .env 를 읽어 export 한다. 다른 스크립트에서 source 해서 사용한다.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export REPO_ROOT

if [[ -f "$REPO_ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$REPO_ROOT/.env"
  set +a
else
  echo "경고: .env 가 없다. 'cp .env.example .env' 후 다시 실행한다." >&2
fi

: "${GATEWAY_PORT:=8080}"
: "${MARKET_PORT:=8081}"
: "${TRADING_PORT:=8082}"
: "${USER_PORT:=8083}"
# 시세 수집 앱 (CLAUDE.md §66). 사용자 요청은 받지 않는다. health 확인용 포트다.
: "${MARKET_INGEST_PORT:=8084}"
export GATEWAY_PORT MARKET_PORT TRADING_PORT USER_PORT MARKET_INGEST_PORT

SERVICE_NAMES=(gateway-service market-service market-ingest trading-service user-service)
SERVICE_PORTS=("$GATEWAY_PORT" "$MARKET_PORT" "$MARKET_INGEST_PORT" "$TRADING_PORT" "$USER_PORT")
export SERVICE_NAMES SERVICE_PORTS

RUN_DIR="$REPO_ROOT/infra/.run"
export RUN_DIR
