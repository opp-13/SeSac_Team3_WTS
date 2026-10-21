// 테스트 종목 고르기. 모의 시세(결정형) 종목만 쓴다: KIS 고정 5종목은 빼고,
// 상장 · 기준가가 있는 종목(SyntheticQuoteFeed의 대상 조건)만 남긴다.
import http from 'k6/http';
import { fail } from 'k6';
import { BASE_URL, PINNED, SYMBOLS, SYMBOL_COUNT } from './config.js';

export function pickSymbols(n = SYMBOL_COUNT) {
  if (SYMBOLS) return SYMBOLS.split(',').slice(0, n);
  const picked = [];
  for (let page = 0; picked.length < n && page < 10; page++) {
    const res = http.get(`${BASE_URL}/api/market/stocks?market=KOSPI&page=${page}&size=100`, {
      tags: { kind: 'setup', name: 'GET /api/market/stocks' },
    });
    if (res.status === 0) {
      fail(`게이트웨이에 연결할 수 없다(${BASE_URL}): ${res.error}. 앱이 떠 있는지 확인한다(로컬: ./infra/scripts/health-check.sh)`);
    }
    if (res.status !== 200) fail(`종목 목록 실패: ${res.status} ${res.body}`);
    const items = res.json('items') || [];
    if (items.length === 0) break;
    for (const s of items) {
      if (picked.length >= n) break;
      if (s.tradable === false || s.basePrice === null || s.basePrice === undefined) continue;
      if (PINNED.includes(s.symbol)) continue;
      picked.push(s.symbol);
    }
  }
  if (picked.length < n) fail(`종목이 ${n}개가 안 된다(${picked.length}개). -e SYMBOL_COUNT를 줄인다`);
  return picked;
}

// 결정형 회전: VU마다 다른 종목에서 시작하게 한다(전체 종목이 고르게 보이도록).
export function rotate(list, offset, count) {
  const out = [];
  for (let i = 0; i < count; i++) out.push(list[(offset + i) % list.length]);
  return out;
}
