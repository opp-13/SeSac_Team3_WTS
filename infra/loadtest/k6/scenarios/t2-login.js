// T2-LOGIN: 로그인 1건당 비용(계획서 §7.4 T2). RATE 로그인/s를 DURATION 동안 건다.
//   k6 run -e RATE=10 -e DURATION=5m scenarios/t2-login.js          # 이미 있는 계정(DB 조회 1회)
//   k6 run -e RATE=10 -e NEW=1 scenarios/t2-login.js                # 새 계정(조회 + insert)
// NEW=1은 lt-new-… 계정을 계속 만든다. 정리할 때 이 접두사로 지운다(런북 §7).
import exec from 'k6/execution';
import { COMMON_OPTIONS, EMAIL_DOMAIN, EMAIL_PREFIX, num, perRunner, RUNNER_INDEX, str, thresholds } from '../lib/config.js';
import { emailOf, login, loginPool } from '../lib/auth.js';

const RATE = perRunner(num('RATE', 10));
const NEW = num('NEW', 0) === 1;
const POOL = num('POOL', 500);

export const options = Object.assign({}, COMMON_OPTIONS, {
  scenarios: {
    logins: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: str('DURATION', '5m'),
      preAllocatedVUs: Math.max(5, RATE),
      maxVUs: Math.max(20, RATE * 4),
      exec: 'doLogin',
    },
  },
  thresholds: thresholds(['login', 'validity']),
});

export function setup() {
  // 기존 계정 회차는 계정을 먼저 만들어 둔다(첫 로그인의 insert를 측정에서 뺀다).
  if (!NEW) loginPool(POOL, { openAccount: false });
  return {};
}

export function doLogin() {
  if (NEW) {
    const id = `${RUNNER_INDEX}-${exec.vu.idInTest}-${exec.vu.iterationInScenario}-${Date.now()}`;
    login(`${EMAIL_PREFIX}-new-${id}@${EMAIL_DOMAIN}`);
  } else {
    login(emailOf(Math.floor(Math.random() * POOL)));
  }
}
