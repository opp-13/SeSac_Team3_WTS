// T3 평균 · T4 stress · T5 breakpoint · T6 오토스케일 · T7 spike · T8 soak · T9 회귀.
// 페르소나(계획서 §4.2)를 섞은 시나리오 부하. 동시 세션 수 N(1배)을 직접 건다:
//   페르소나마다 ramping-vus, 목표 VU = N × 비율 × 배수(PROFILE). VU 하나는 세션을 연달아 연다.
//   세션 도착률은 N ÷ 평균 세션 길이로 따라온다(Little, 계획서 §4.2). 세션 사이 쉬는 시간은 지수분포다.
//
//   k6 run -e BASE_URL=... -e N=100 -e PROFILE=steady scenarios/mixed.js
//
// -e N        1배일 때 동시 세션 수(전체. 러너가 여럿이면 lt-run.sh가 나눈다). 기본 100
// -e PROFILE  steady|stress|breakpoint|autoscale|spike|soak (lib/profile.js)
// -e PEAK     autoscale · spike의 배수. 기본 3
// -e DURATION steady · spike · soak의 유지 시간
// -e LOGIN_SHARE 확인형 세션 중 다시 로그인하는 비율. 기본 0.1
import exec from 'k6/execution';
import { COMMON_OPTIONS, num, perRunner, RUNNER_INDEX, str, thresholds } from '../lib/config.js';
import { loginPool } from '../lib/auth.js';
import { pickSymbols } from '../lib/symbols.js';
import { marketSession } from '../lib/ws.js';
import { PERSONAS, SESSION_FN, thinkBetweenSessions } from '../lib/personas.js';
import { profileStages, totalMs } from '../lib/profile.js';

const N = perRunner(num('N', 100));
const PROFILE = str('PROFILE', 'steady');
const STAGES = profileStages(PROFILE);
const TOTAL_MS = totalMs(STAGES);

const scenarios = {};
let maxVus = 0;
for (const [name, p] of Object.entries(PERSONAS)) {
  const stages = STAGES.map(([duration, mult]) => ({ duration, target: Math.round(N * p.share * mult) }));
  const peak = Math.max(...stages.map((s) => s.target));
  if (peak === 0) continue;
  maxVus += peak;
  scenarios[name] = {
    executor: 'ramping-vus',
    startVUs: 0,
    stages,
    gracefulRampDown: '30s',
    gracefulStop: '30s',
    exec: 'persona',
    env: { PERSONA: name },
    tags: { persona: name },
  };
}
// 시세 keeper: 테스트 종목 전체를 계속 구독해서 모든 종목에 가격이 있게 한다(주문이 503이 되지 않게).
if (RUNNER_INDEX === 0) {
  scenarios.keeper = { executor: 'constant-vus', vus: 1, duration: `${Math.ceil(TOTAL_MS / 1000)}s`, exec: 'keeper', gracefulStop: '10s' };
}

const POOL = num('POOL', maxVus + 1);

export const options = Object.assign({}, COMMON_OPTIONS, {
  scenarios,
  thresholds: thresholds(['query', 'order', 'login', 'ws']),
});

export function setup() {
  const symbols = pickSymbols();
  const tokens = loginPool(POOL);
  console.log(`mixed PROFILE=${PROFILE} N(이 러너, 1배 동시 세션)=${N} 최대VU=${maxVus} 계정=${tokens.length} 종목=${symbols.length}`);
  // 1배일 때 페르소나별 예상 세션 도착률 λᵢ = pᵢN / (Wᵢ + 쉬는 시간) (계획서 §4.2). 실측 k6_sessions_started와 비교한다.
  const think = num('THINK_SEC', 5) / 60;
  let total = 0;
  for (const [name, p] of Object.entries(PERSONAS)) {
    const lambda = (N * p.share) / ((p.minMin + p.maxMin) / 2 + think);
    total += lambda;
    console.log(`  ${name}: 동시 ${Math.round(N * p.share)}세션, 평균 ${(p.minMin + p.maxMin) / 2}분 → λ ≈ ${lambda.toFixed(2)} 세션/분`);
  }
  console.log(`  합계 λ ≈ ${total.toFixed(1)} 세션/분 = 새 WS 연결 ${(total / 60).toFixed(2)}/s (재연결 · DRAIN 제외)`);
  return { symbols, tokens };
}

export function persona(data) {
  // VU마다 계정 하나: 같은 계좌로 동시에 주문하지 않는다(계좌 비관적 락 경합만 재게 되는 것을 피함, 계획서 §2.1).
  const vu = exec.vu.idInTest;
  const account = (vu - 1) % data.tokens.length;
  thinkBetweenSessions(num('THINK_SEC', 5));
  SESSION_FN[__ENV.PERSONA]({
    token: data.tokens[account],
    account,
    symbols: data.symbols,
    offset: (vu * 7) % data.symbols.length,
    loginShare: num('LOGIN_SHARE', 0.1),
  });
}

export function keeper(data) {
  marketSession({ symbols: data.symbols, durationMs: TOTAL_MS, tags: { persona: 'keeper' } });
}
