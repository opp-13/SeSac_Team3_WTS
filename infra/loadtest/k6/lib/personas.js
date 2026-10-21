// 계획서 §4.2 페르소나. 함수 하나 = 세션 하나(접속 → 행동 → 이탈).
// 비율 · 세션 길이는 시나리오 가정이다(계획서 §3.2 A2~A4). 실측 사용자 분포가 아니다. 바꾸려면 PERSONAS 표만 고친다.
import { sleep } from 'k6';
import { marketSession } from './ws.js';
import { rotate } from './symbols.js';
import { sessionsStarted } from './metrics.js';
import {
  cancelOrder,
  candles,
  pendingOrders,
  placeOrder,
  portfolio,
  price,
  stockDetail,
  stockList,
  watchlist,
} from './trading.js';
import { login, emailOf } from './auth.js';

// share: 동시 세션 중 비율, minMin~maxMin: 세션 길이(분)
export const PERSONAS = {
  checker: { share: 0.5, minMin: 1, maxMin: 3 }, // 확인형
  watcher: { share: 0.3, minMin: 5, maxMin: 15 }, // 관전자
  trader: { share: 0.15, minMin: 5, maxMin: 10 }, // 일반 매매자
  daytrader: { share: 0.05, minMin: 30, maxMin: 60 }, // 단타
};

const LIST_ROWS = 20; // 종목 목록 화면에 보이는 행 = LIST 구독 수

function between(a, b) {
  return a + Math.random() * (b - a);
}

function sessionMs(p) {
  return Math.round(between(p.minMin, p.maxMin) * 60000);
}

// 세션과 세션 사이 쉬는 시간(지수분포, 평균 meanSec). 도착 간격에 변동을 준다(Kingman, 계획서 §8.1).
// 반복의 맨 앞에서 부른다. 세션을 연 뒤에 sleep하면 그동안 WS 이벤트가 처리되지 않는다.
export function thinkBetweenSessions(meanSec = 5) {
  sleep(-Math.log(1 - Math.random()) * meanSec);
}

// /wts 화면 진입. 실제 프런트엔드(WtsPage.tsx)는 선택 종목 + 왼쪽 목록(검색 · 관심종목)만 구독하고,
// 로그인 상태면 관심종목을 조회한다. /portfolio · /orders · /login은 연결만 하고 구독은 0이다(계획서 §4.3).
function openWtsPage(ctx) {
  stockList(0);
  if (ctx.token) watchlist(ctx.token);
}

// 목록 화면 + 상세 1종목 구독 상태를 만든다.
function listAndFocus(symbols, offset) {
  const list = rotate(symbols, offset, Math.min(LIST_ROWS, symbols.length));
  const focus = symbols[(offset + LIST_ROWS) % symbols.length];
  return { list, focus };
}

// 확인형: 관심종목 또는 포트폴리오를 1~3분 보고 나간다. 주문 없음.
// 가끔(LOGIN_SHARE) 다시 로그인한다(앱을 새로 연 경우).
export function checkerSession(ctx) {
  const p = PERSONAS.checker;
  sessionsStarted.add(1, { persona: 'checker' });
  if (Math.random() < ctx.loginShare) login(emailOf(ctx.account));
  const lookAtPortfolio = Math.random() < 0.3;
  if (lookAtPortfolio) {
    portfolio(ctx.token);
    marketSession({ symbols: [], durationMs: sessionMs(p), tags: { persona: 'checker' } });
    return;
  }
  openWtsPage(ctx);
  const { list } = listAndFocus(ctx.symbols, ctx.offset);
  marketSession({ symbols: list, durationMs: sessionMs(p), tags: { persona: 'checker' } });
}

// 관전자: 목록(20) + 상세(1)를 보며 30~60s마다 상세 종목을 바꾼다. 차트 60s 폴링.
export function watcherSession(ctx, persona = 'watcher', extraEvery = [], onEnd = null, durationMs = 0) {
  const p = PERSONAS[persona];
  sessionsStarted.add(1, { persona });
  openWtsPage(ctx);
  let { list, focus } = listAndFocus(ctx.symbols, ctx.offset);
  stockDetail(focus);
  candles(focus);
  let step = 1;
  marketSession({
    symbols: list.concat([focus]),
    durationMs: durationMs || sessionMs(p),
    tags: { persona },
    every: [
      {
        ms: Math.round(between(30, 60) * 1000),
        fn: (ctl) => {
          const next = ctx.symbols[(ctx.offset + LIST_ROWS + step++) % ctx.symbols.length];
          if (!list.includes(focus)) ctl.unsubscribe([focus]);
          focus = next;
          ctl.subscribe([focus]);
          stockDetail(focus);
          candles(focus);
        },
      },
      { ms: 60000, fn: () => candles(focus) },
    ].concat(extraEvery),
    onEnd,
  });
}

// 매매 공통: 주문 1건. 70% 시장가(보유하면 절반은 매도), 30% 현재가 -3% 지정가 매수(미체결로 남는다).
function tradeOnce(ctx, state) {
  const symbol = ctx.symbols[Math.floor(Math.random() * ctx.symbols.length)];
  if (Math.random() < 0.7) {
    const sell = state.held[symbol] > 0 && Math.random() < 0.5;
    const o = placeOrder(ctx.token, { symbol, side: sell ? 'SELL' : 'BUY', orderType: 'MARKET', quantity: 1 });
    if (o && o.status === 'FILLED') state.held[symbol] = (state.held[symbol] || 0) + (sell ? -1 : 1);
  } else {
    const now = price(symbol);
    if (!now) return;
    const limitPrice = Math.max(1, Math.floor(now * 0.97));
    const o = placeOrder(ctx.token, { symbol, side: 'BUY', orderType: 'LIMIT', quantity: 1, limitPrice });
    if (o && o.status === 'ACCEPTED') state.pending.push(o.orderId);
  }
}

// 포트폴리오 10s 폴링, 미체결이 있으면 주문 목록 3s 폴링. 세션 끝에 미체결 절반을 취소한다.
function tradingEvery(ctx, state, orderEveryMs) {
  return [
    { ms: 10000, fn: () => portfolio(ctx.token) },
    {
      ms: 3000,
      fn: () => {
        if (state.pending.length === 0) return;
        const open = pendingOrders(ctx.token).map((o) => o.orderId);
        state.pending = state.pending.filter((id) => open.includes(id));
      },
    },
    { ms: orderEveryMs, fn: () => tradeOnce(ctx, state) },
  ];
}

function cancelHalf(ctx, state) {
  state.pending.filter(() => Math.random() < 0.5).forEach((id) => cancelOrder(ctx.token, id));
}

// 일반 매매자: 관전자 + 세션당 주문 1~3건.
export function traderSession(ctx) {
  const p = PERSONAS.trader;
  const state = { held: {}, pending: [] };
  const lengthMs = sessionMs(p);
  const orders = 1 + Math.floor(Math.random() * 3);
  // 세션 길이 안에 주문이 orders번 들어가도록 간격을 잡는다.
  const every = Math.round(lengthMs / (orders + 0.5));
  watcherSession(ctx, 'trader', tradingEvery(ctx, state, every), () => cancelHalf(ctx, state), lengthMs);
}

// 단타: 1~3분마다 주문.
export function daytraderSession(ctx) {
  const state = { held: {}, pending: [] };
  tradeOnce(ctx, state);
  watcherSession(ctx, 'daytrader', tradingEvery(ctx, state, Math.round(between(60, 180) * 1000)), () =>
    cancelHalf(ctx, state),
  );
}

export const SESSION_FN = {
  checker: checkerSession,
  watcher: (ctx) => watcherSession(ctx, 'watcher'),
  trader: traderSession,
  daytrader: daytraderSession,
};
