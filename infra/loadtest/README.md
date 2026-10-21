# 부하 테스트 (k6)

- 계획(무엇을 왜 재나): trader-terraform-sessac `docs/experiment/wts-load-test-plan.md`
- **런북(명령어 순서)**: trader-terraform-sessac `docs/experiment/wts-load-test-runbook.md`

## 로컬에서 먼저 (5분)

```bash
brew install k6                      # 처음 한 번
docker compose up -d && ./infra/scripts/run-backend.sh && ./infra/scripts/health-check.sh
cd infra/loadtest/k6
k6 run scenarios/t0-smoke.js         # 종료 코드 0이면 스크립트 · API 계약이 맞다
```

## 구성

| 경로 | 내용 |
|---|---|
| `k6/scenarios/t0-smoke.js` | T0 smoke + 모든 API 기능 확인 |
| `k6/scenarios/t2-ws.js` | T2 WebSocket 단위 비용(MODE=conn · scan · msg) |
| `k6/scenarios/t2-order.js` | T2 주문 단위 비용(RATE 주문/s) |
| `k6/scenarios/t2-login.js` | T2 로그인 단위 비용(RATE 로그인/s) |
| `k6/scenarios/mixed.js` | 페르소나 혼합(PROFILE=steady · stress · breakpoint · autoscale · spike · soak) |
| `k6/lib/` | 설정 · thresholds(합격 기준), 로그인, 종목, WS 세션, 주문, 페르소나, 부하 모양 |
| `bin/lt-*.sh` | 클러스터 실행(터널 · 실행 · 중단 · 회수 · 타임라인 · 정합성 SQL) |
| `bin/lt-export.py`, `bin/lt-analyze.py` | Prometheus → CSV, 단위 비용 회귀 |
| `results/` | 회차 결과(Git에 넣지 않는다) |

모든 시나리오의 값은 `-e KEY=VALUE`로 바꾼다. 파일 맨 위 주석에 쓸 수 있는 값이 있다.
