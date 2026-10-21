// T0 smoke: 스크립트 · 계정 · 지표 경로가 맞는지 본다. 가장 먼저, 로컬에서도 돌린다.
//   k6 run scenarios/t0-smoke.js                                  # 로컬(run-backend.sh)
//   k6 run -e BASE_URL=http://gateway-service.wts.svc:8080 ...     # 클러스터(lt-run.sh가 넣는다)
// 합격: 모든 check 통과, 모든 threshold 통과(종료 코드 0).
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { COMMON_OPTIONS, num, thresholds } from '../lib/config.js';
import { loginPool } from '../lib/auth.js';
import { pickSymbols } from '../lib/symbols.js';
import { marketSession } from '../lib/ws.js';
import { SESSION_FN } from '../lib/personas.js';
import {
  account,
  cancelOrder,
  candles,
  pendingOrders,
  placeOrder,
  portfolio,
  price,
  stockDetail,
  stockList,
} from '../lib/trading.js';

const DURATION = __ENV.DURATION || '5m';

export const options = Object.assign({}, COMMON_OPTIONS, {
  scenarios: {
    keeper: { executor: 'constant-vus', vus: 1, duration: DURATION, exec: 'keeper', gracefulStop: '10s' },
    // 기능 확인: 모든 API를 한 번씩. 시세가 돌기 시작하도록 keeper보다 15초 늦게 시작한다.
    functional: { executor: 'shared-iterations', vus: 1, iterations: 1, startTime: '15s', exec: 'functional' },
    checker: personaScenario('checker', 2),
    watcher: personaScenario('watcher', 1),
    trader: personaScenario('trader', 1),
    daytrader: personaScenario('daytrader', 1),
  },
  thresholds: Object.assign(thresholds(['query', 'order', 'login', 'ws'], { killSwitch: false }), {
    'checks{scenario:functional}': ['rate==1'],
  }),
});

function personaScenario(name, vus) {
  return { executor: 'constant-vus', vus, duration: DURATION, startTime: '15s', exec: 'persona', env: { PERSONA: name }, gracefulStop: '20s' };
}

export function setup() {
  return { symbols: pickSymbols(), tokens: loginPool(8) };
}

export function keeper(data) {
  marketSession({ symbols: data.symbols, durationMs: 60 * 60000, tags: { persona: 'keeper' } });
}

export function persona(data) {
  const vu = exec.vu.idInTest;
  const idx = vu % data.tokens.length;
  SESSION_FN[__ENV.PERSONA]({
    token: data.tokens[idx],
    account: idx,
    symbols: data.symbols,
    offset: (vu * 7) % data.symbols.length,
    loginShare: num('LOGIN_SHARE', 0.1),
  });
}

export function functional(data) {
  const token = data.tokens[0];
  const s = data.symbols[0];
  const ok = (name, res, status) => check(res, { [name]: (r) => r.status === status });

  ok('GET stocks', stockList(0), 200);
  ok('GET stock detail', stockDetail(s), 200);
  ok('GET candles', candles(s), 200);
  check(price(s), { 'GET price (시세가 돌고 있다)': (p) => p !== null && p > 0 });
  ok('GET account', account(token), 200);

  const buy = placeOrder(token, { symbol: s, side: 'BUY', orderType: 'MARKET', quantity: 1 });
  check(buy, { 'MARKET BUY FILLED': (o) => o !== null && o.status === 'FILLED' });
  const sell = placeOrder(token, { symbol: s, side: 'SELL', orderType: 'MARKET', quantity: 1 });
  check(sell, { 'MARKET SELL FILLED': (o) => o !== null && o.status === 'FILLED' });

  const now = price(s);
  const limit = placeOrder(token, { symbol: s, side: 'BUY', orderType: 'LIMIT', quantity: 1, limitPrice: Math.max(1, Math.floor(now * 0.5)) });
  check(limit, { 'LIMIT BUY ACCEPTED(미체결)': (o) => o !== null && o.status === 'ACCEPTED' });
  sleep(1);
  check(pendingOrders(token), { 'GET orders?status=ACCEPTED에 보인다': (list) => limit !== null && list.some((o) => o.orderId === limit.orderId) });
  if (limit) ok('DELETE order', cancelOrder(token, limit.orderId), 200);
  ok('GET portfolio', portfolio(token), 200);
}
