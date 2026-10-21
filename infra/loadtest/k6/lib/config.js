// 모든 k6 시나리오의 공통 설정.
// 값은 전부 `k6 run -e KEY=VALUE`로 덮어쓴다. 기본값은 로컬(run-backend.sh) 기준이다.
// 계획서: trader-terraform-sessac docs/experiment/wts-load-test-plan.md
// 런북:   trader-terraform-sessac docs/experiment/wts-load-test-runbook.md
import './metrics.js';

export function str(name, def) {
  const v = __ENV[name];
  return v === undefined || v === '' ? def : v;
}

export function num(name, def) {
  const v = __ENV[name];
  if (v === undefined || v === '') return def;
  const n = Number(v);
  if (Number.isNaN(n)) throw new Error(`-e ${name}=${v} 는 숫자가 아니다`);
  return n;
}

// 게이트웨이 주소. 클러스터 안(발생기 파드)에서는 http://gateway-service.wts.svc:8080
export const BASE_URL = str('BASE_URL', 'http://localhost:8080').replace(/\/$/, '');
export const WS_URL = str('WS_URL', `${BASE_URL.replace(/^http/, 'ws')}/ws/market`);

// 테스트 종목 수. 모의 시세는 동시에 보는 종목이 100개를 넘으면 오래된 것부터 멈춘다
// (market.synthetic.max-symbols). 실제 사용자 · ws-gap-probe 몫을 남기도록 50으로 둔다.
export const SYMBOL_COUNT = num('SYMBOL_COUNT', 50);
// KIS 실시간 고정 종목(market.pinned-symbols). 장 마감 뒤에는 시세가 멈추므로 주문에 쓰지 않는다.
export const PINNED = str('PINNED', '005930,000660,035420,035720,005380').split(',');
// 종목을 직접 지정할 때(쉼표 구분). 비우면 종목 API에서 고른다.
export const SYMBOLS = str('SYMBOLS', '');

// 시험 계정: lt-r<러너번호>-<번호>@loadtest.local (Mock Login, ADR-0020). 다른 계정은 건드리지 않는다.
export const EMAIL_PREFIX = str('EMAIL_PREFIX', 'lt');
export const EMAIL_DOMAIN = 'loadtest.local';

// 발생기 파드가 여러 개면 lt-run.sh가 넣는다. 부하(N · RATE)를 러너 수로 나누고, 계정이 겹치지 않게 한다.
export const RUNNERS = num('RUNNERS', 1);
export const RUNNER_INDEX = num('RUNNER_INDEX', 0);

export function perRunner(total) {
  return Math.max(0, Math.round(total / RUNNERS));
}

// WS 지연 · 간격은 메시지마다 다 재면 발생기 메모리가 터진다(Trend는 표본을 모두 들고 있다).
// 이 비율만큼만 표본으로 남긴다. 메시지 수(Counter)는 전부 센다.
export const LAG_SAMPLE = num('LAG_SAMPLE', 0.02);

// 합격 기준(계획서 §1.2)과 중단 조건(§5.4)을 k6 thresholds로 둔다.
// 합격 기준을 어기면 k6가 0이 아닌 종료 코드로 끝난다(k6 원칙: 목표 지향).
// 중단 조건은 abortOnFail: 1분 동안 지켜보다가 어기면 즉시 멈춘다.
const GROUPS = {
  query: {
    'http_req_failed{kind:query}': ['rate<0.001'],
    'http_req_duration{kind:query}': ['p(95)<300', 'p(99)<1000'],
  },
  order: {
    'http_req_failed{kind:order}': ['rate<0.001'],
    'http_req_duration{kind:order}': ['p(95)<500', 'p(99)<1500'],
    'checks{kind:order}': ['rate>0.999'],
    order_price_missing: ['count<1'],
  },
  login: {
    'http_req_duration{kind:login}': ['p(95)<500'],
    'checks{kind:login}': ['rate>0.999'],
  },
  ws: {
    ws_connect_ok: ['rate>0.999'],
    ws_connect_ms: ['p(95)<1000'],
    ws_quote_lag_ms: ['p(95)<1500'],
  },
  // 발생기 유효성(계획서 §6.4). 도착률(open) 시나리오에서 VU가 모자라 시작하지 못한 반복이 있으면
  // 계획한 부하를 실제로 걸지 못한 것이다 → 이 회차는 대상 시스템의 한계로 해석하지 않는다(무효 회차).
  validity: {
    dropped_iterations: ['count<1'],
  },
};

const KILL_SWITCH = {
  http_req_failed: [{ threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '1m' }],
  http_req_duration: [{ threshold: 'p(99)<5000', abortOnFail: true, delayAbortEval: '1m' }],
};

export function thresholds(groups, { killSwitch = true } = {}) {
  const out = {};
  for (const g of groups) Object.assign(out, GROUPS[g]);
  if (killSwitch) Object.assign(out, KILL_SWITCH);
  return out;
}

// 모든 시나리오 공통 옵션. setup에서 계정 수천 개를 로그인하므로 setupTimeout을 늘린다.
export const COMMON_OPTIONS = {
  setupTimeout: '10m',
  teardownTimeout: '2m',
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};
