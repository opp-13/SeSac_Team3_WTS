// T2-ORDER: 주문 1건당 비용(계획서 §7.4 T2). 도착률(open 모델)로 RATE 주문/s를 DURATION 동안 건다.
// RATE 값마다 한 번씩 돌린다(런북: 셸 루프 10 → 25 → 50 → 100 → 200).
//
//   k6 run -e RATE=50 -e DURATION=5m scenarios/t2-order.js
//
// 주문 모양: VU마다 계좌 하나(같은 계좌 동시 주문 없음). 같은 종목을 시장가로 1주 사고 → 팔고를 번갈아 해서
// 예수금 · 보유가 그대로 유지된다. -e LIMIT_SHARE=0.2면 20%는 현재가 -3% 지정가 매수(미체결로 남음).
// keeper가 테스트 종목을 계속 구독해야 주문이 503(MARKET_PRICE_UNAVAILABLE/STALE)이 되지 않는다.
import exec from 'k6/execution';
import { COMMON_OPTIONS, num, perRunner, RUNNER_INDEX, str, thresholds } from '../lib/config.js';
import { loginPool } from '../lib/auth.js';
import { pickSymbols } from '../lib/symbols.js';
import { marketSession } from '../lib/ws.js';
import { placeOrder, price } from '../lib/trading.js';
import { durationMs } from '../lib/profile.js';

const RATE = perRunner(num('RATE', 10));
const DURATION = str('DURATION', '5m');
const WARMUP = '20s'; // keeper가 붙고 시세가 돌기 시작할 시간
const MAX_VUS = Math.max(20, RATE * 4);

const scenarios = {
  orders: {
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: Math.max(10, RATE),
    maxVUs: MAX_VUS,
    startTime: WARMUP,
    exec: 'order',
  },
};
if (RUNNER_INDEX === 0) {
  scenarios.keeper = {
    executor: 'constant-vus',
    vus: 1,
    duration: `${Math.ceil((durationMs(DURATION) + durationMs(WARMUP) + 30000) / 1000)}s`,
    exec: 'keeper',
    gracefulStop: '10s',
  };
}

export const options = Object.assign({}, COMMON_OPTIONS, {
  scenarios,
  thresholds: thresholds(['order', 'validity']),
});

export function setup() {
  return { symbols: pickSymbols(), tokens: loginPool(num('POOL', MAX_VUS)) };
}

export function order(data) {
  const vu = exec.vu.idInTest;
  const k = exec.vu.iterationInScenario;
  const token = data.tokens[(vu - 1) % data.tokens.length];
  const symbol = data.symbols[(vu + Math.floor(k / 2)) % data.symbols.length];
  if (Math.random() < num('LIMIT_SHARE', 0)) {
    const now = price(symbol);
    if (now) placeOrder(token, { symbol, side: 'BUY', orderType: 'LIMIT', quantity: 1, limitPrice: Math.max(1, Math.floor(now * 0.97)) });
    return;
  }
  placeOrder(token, { symbol, side: k % 2 === 0 ? 'BUY' : 'SELL', orderType: 'MARKET', quantity: 1 });
}

export function keeper(data) {
  marketSession({ symbols: data.symbols, durationMs: durationMs(DURATION) + 60000, tags: { persona: 'keeper' } });
}
