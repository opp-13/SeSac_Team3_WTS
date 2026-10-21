// T2-WS: WebSocket 단위 비용(계획서 §7.4 T2). HPA를 고정한 상태에서 한 종류의 부하만 올린다.
//
// MODE=conn   연결만(구독 0). 연결 수를 STEPS 계단으로 늘린다. keeper 없음 → 틱 없음 → 연결 유지 비용.
// MODE=scan   conn과 같고, keeper가 테스트 종목 전체를 구독해 틱이 흐른다 → 틱마다 전 세션 스캔 비용.
// MODE=msg    연결 CONNS개, 연결마다 SUBS종목 구독. SUBS 값마다 한 번씩 돌린다(런북: 셸 루프).
// MODE=churn  연결 생성률(open 모델): 초당 RATE개 새 연결, 각 연결은 SUBS종목 구독 후 HOLD_SEC초 유지.
//             핸드셰이크 + 구독 직후 스냅샷 전송 비용(계획서 §4.6). RATE 값마다 한 번씩 돌린다.
//
//   k6 run -e MODE=conn -e STEPS=250,500,1000,2000 -e STEP_DURATION=5m scenarios/t2-ws.js
//   k6 run -e MODE=msg -e CONNS=500 -e SUBS=10 -e STEP_DURATION=5m scenarios/t2-ws.js
//   k6 run -e MODE=churn -e RATE=20 -e HOLD_SEC=30 -e SUBS=20 -e STEP_DURATION=5m scenarios/t2-ws.js
import exec from 'k6/execution';
import { COMMON_OPTIONS, num, perRunner, RUNNER_INDEX, str, thresholds } from '../lib/config.js';
import { pickSymbols, rotate } from '../lib/symbols.js';
import { marketSession } from '../lib/ws.js';
import { durationMs, totalMs } from '../lib/profile.js';

const MODE = str('MODE', 'conn');
const STEP = str('STEP_DURATION', '5m');

const scenarios = {};
let stages;
let groups = ['ws'];
if (MODE === 'conn' || MODE === 'scan') {
  const steps = str('STEPS', '250,500,1000,2000').split(',').map((v) => perRunner(Number(v)));
  stages = [];
  for (const target of steps) stages.push({ duration: '30s', target }, { duration: STEP, target });
  stages.push({ duration: '30s', target: 0 });
  scenarios.holders = { executor: 'ramping-vus', startVUs: 0, stages, gracefulRampDown: '10s', exec: 'holder' };
} else if (MODE === 'msg') {
  stages = [{ duration: STEP }];
  scenarios.subscribers = {
    executor: 'constant-vus',
    vus: perRunner(num('CONNS', 500)),
    duration: STEP,
    gracefulStop: '10s',
    exec: 'subscriber',
  };
} else if (MODE === 'churn') {
  const rate = perRunner(num('RATE', 10));
  const hold = num('HOLD_SEC', 30);
  stages = [{ duration: STEP }];
  // 동시에 열린 연결 ≈ RATE × HOLD_SEC(Little). VU는 그 1.5배 + 여유를 준다.
  const concurrent = Math.ceil(rate * hold * 1.5) + 10;
  scenarios.churners = {
    executor: 'constant-arrival-rate',
    rate,
    timeUnit: '1s',
    duration: STEP,
    preAllocatedVUs: concurrent,
    maxVUs: concurrent * 2,
    gracefulStop: `${hold + 10}s`,
    exec: 'churner',
  };
  groups = ['ws', 'validity'];
} else {
  throw new Error(`MODE=${MODE} 는 conn|scan|msg|churn 중 하나`);
}
const TOTAL_MS = totalMs(stages.map((s) => [s.duration]));
if ((MODE === 'scan' || MODE === 'churn') && RUNNER_INDEX === 0) {
  scenarios.keeper = { executor: 'constant-vus', vus: 1, duration: `${Math.ceil(TOTAL_MS / 1000) + 60}s`, exec: 'keeper', gracefulStop: '10s' };
}

export const options = Object.assign({}, COMMON_OPTIONS, {
  scenarios,
  thresholds: thresholds(groups, { killSwitch: false }),
});

export function setup() {
  return { symbols: pickSymbols() };
}

// 연결 하나를 테스트 끝까지 쥐고 있는다(구독 0, PING만).
export function holder() {
  marketSession({ symbols: [], durationMs: TOTAL_MS, tags: { mode: MODE } });
}

export function subscriber(data) {
  const subs = num('SUBS', 1);
  const symbols = rotate(data.symbols, exec.vu.idInTest, subs);
  marketSession({ symbols, durationMs: durationMs(STEP), tags: { mode: MODE, subs: String(subs) } });
}

// 새 연결 하나: 연결 → 구독(직후 스냅샷을 받는다) → HOLD_SEC 유지 → 닫기.
export function churner(data) {
  const subs = num('SUBS', 20);
  const symbols = rotate(data.symbols, exec.scenario.iterationInTest, subs);
  marketSession({ symbols, durationMs: num('HOLD_SEC', 30) * 1000, tags: { mode: MODE } });
}

export function keeper(data) {
  marketSession({ symbols: data.symbols, durationMs: TOTAL_MS + 60000, tags: { persona: 'keeper' } });
}
