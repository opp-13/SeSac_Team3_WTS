// Mock Login(ADR-0020)과 시험 계정 묶음.
// 토큰은 무상태 HMAC이고 TTL 24h라서, setup()에서 한 번 받아 VU들에게 나눠 준다(CSV 불필요).
import http from 'k6/http';
import { check, fail } from 'k6';
import { BASE_URL, EMAIL_DOMAIN, EMAIL_PREFIX, RUNNER_INDEX } from './config.js';

const JSON_HEADERS = { 'Content-Type': 'application/json' };
const BATCH = 50;

export function emailOf(i) {
  return `${EMAIL_PREFIX}-r${RUNNER_INDEX}-${String(i).padStart(5, '0')}@${EMAIL_DOMAIN}`;
}

export function authParams(token, kind, name, extra = {}) {
  const params = {
    headers: Object.assign({ Authorization: `Bearer ${token}` }, JSON_HEADERS, extra.headers || {}),
    tags: Object.assign({ kind, name }, extra.tags || {}),
  };
  // responseCallback 키를 undefined로라도 넣으면 k6는 그 요청을 http_req_failed에서 뺀다(실패가 집계되지 않음).
  // 따로 줄 때만 넣는다. 없으면 k6 기본값(200~399가 성공)을 쓴다.
  if (extra.responseCallback) params.responseCallback = extra.responseCallback;
  return params;
}

function loginRequest(email) {
  return [
    'POST',
    `${BASE_URL}/api/users/mock-login`,
    JSON.stringify({ email }),
    { headers: JSON_HEADERS, tags: { kind: 'login', name: 'POST /api/users/mock-login' } },
  ];
}

// 한 명 로그인. 실패하면 null.
export function login(email) {
  const [method, url, body, params] = loginRequest(email);
  const res = http.request(method, url, body, params);
  const ok = check(res, { 'login 200': (r) => r.status === 200 }, { kind: 'login' });
  return ok ? res.json('accessToken') : null;
}

// n명을 50명씩 병렬로 로그인하고, openAccount면 GET /account로 계좌(1억 원)를 미리 연다.
// 계좌를 미리 열어 두면 동시 첫 주문에서 계좌 생성 경합을 재지 않게 된다.
// setup()에서만 부른다. 반환값은 토큰 배열(인덱스 = 계정 번호).
export function loginPool(n, { openAccount = true } = {}) {
  const tokens = [];
  for (let start = 0; start < n; start += BATCH) {
    const reqs = [];
    for (let i = start; i < Math.min(n, start + BATCH); i++) reqs.push(loginRequest(emailOf(i)));
    const res = http.batch(reqs);
    res.forEach((r, k) => {
      // check를 남겨야 checks{kind:login} threshold가 표본 0개로 "통과"하지 않는다.
      check(r, { 'login 200': (x) => x.status === 200 }, { kind: 'login' });
      if (r.status !== 200) fail(`로그인 실패 ${emailOf(start + k)}: ${r.status} ${r.body}`);
      tokens.push(r.json('accessToken'));
    });
  }
  if (openAccount) {
    for (let start = 0; start < tokens.length; start += BATCH) {
      const reqs = tokens
        .slice(start, start + BATCH)
        .map((t) => ['GET', `${BASE_URL}/api/trading/account`, null, authParams(t, 'setup', 'GET /api/trading/account')]);
      http.batch(reqs).forEach((r) => {
        if (r.status !== 200) fail(`계좌 열기 실패: ${r.status} ${r.body}`);
      });
    }
  }
  return tokens;
}
