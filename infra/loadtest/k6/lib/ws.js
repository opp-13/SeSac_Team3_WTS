// 시세 WebSocket 세션 하나. 브라우저(frontend/src/lib/marketSocket.ts)와 같은 동작:
// 연결 → SUBSCRIBE → 25s마다 PING → DRAIN을 받으면 새 연결을 먼저 열고 옛 연결을 닫는다(ADR-0024)
// → 끊기면 0.5~1.5s 뒤 재연결. Origin 헤더는 보내지 않는다(게이트웨이 CORS 검사를 피함).
//
// 프로토콜(market-service ClientCommand / ServerMessages):
//   보냄  {"type":"SUBSCRIBE","symbols":[..]}  {"type":"UNSUBSCRIBE","symbols":[..]}  {"type":"PING"}
//   받음  SUBSCRIBED · PRICE{symbol,timestamp} · ORDERBOOK · PONG · DRAIN · ERROR
// PRICE.timestamp는 모의 시세 슬롯 시작(1초 내림)이라 지연에는 0~1s가 기본으로 깔린다(계획서 §1.2).
import { WebSocket } from 'k6/websockets';
import { setInterval, setTimeout, clearInterval, clearTimeout } from 'k6/timers';
import { WS_URL, LAG_SAMPLE } from './config.js';
import {
  wsClosed,
  wsConnectMs,
  wsConnectOk,
  wsDrain,
  wsErrors,
  wsMsgs,
  wsOpened,
  wsQuoteGap,
  wsQuoteLag,
} from './metrics.js';

const PING_MS = 25000;

function parseTs(ts) {
  // 소수점 아래가 3자리를 넘으면 Date.parse가 NaN을 줄 수 있어 밀리초까지만 남긴다.
  return Date.parse(String(ts).replace(/(\.\d{3})\d+/, '$1'));
}

// symbols: 처음 구독할 종목. durationMs: 세션 길이. tags: 지표 태그(persona 등).
// every: [{ ms, fn(ctl) }] 세션 중 주기적으로 할 일(REST 호출, 종목 바꾸기).
//   ctl.subscribe(list) / ctl.unsubscribe(list) / ctl.symbols() / ctl.end()
// onEnd(): 세션이 끝날 때 한 번 부른다(미체결 취소 등).
// 주의: 이 함수는 바로 돌아오고, 반복(iteration)은 소켓과 타이머가 모두 닫힐 때까지 이어진다(k6 이벤트 루프).
// 그래서 "세션이 끝난 뒤" 할 일은 이 함수 다음 줄이 아니라 onEnd에 넣는다.
export function marketSession({ symbols = [], durationMs, tags = {}, every = [], onEnd = null }) {
  const subscribed = new Set(symbols);
  const lastPriceAt = {};
  // 세션 중에 다시 구독한 종목. 구독 직후 오는 첫 PRICE는 서버 캐시의 스냅샷이라 지연 · 간격 측정에서 뺀다.
  // 빼지 않으면 "종목을 뺐다가 다시 구독하기까지"가 공백으로 잡힌다(2026-10-21 T3에서 ws_quote_gap_ms 최대 26분).
  const resubscribed = new Set();
  const started = Date.now();
  let current = null;
  let finished = false;
  const intervals = [];
  const timeouts = [];

  const ctl = {
    symbols: () => Array.from(subscribed),
    subscribe(list) {
      list.forEach((s) => {
        subscribed.add(s);
        resubscribed.add(s);
      });
      send({ type: 'SUBSCRIBE', symbols: list });
    },
    unsubscribe(list) {
      list.forEach((s) => {
        subscribed.delete(s);
        delete lastPriceAt[s];
      });
      send({ type: 'UNSUBSCRIBE', symbols: list });
    },
    end,
  };

  function send(obj) {
    if (current && current.readyState === 1) current.send(JSON.stringify(obj));
  }

  function connect(previous) {
    const t0 = Date.now();
    let opened = false;
    let ping = null;
    // 서버는 SUBSCRIBE 직후 캐시에 있던 마지막 시세를 바로 보낸다(스냅샷). 그 timestamp는 몇 초~몇 분 전일 수 있어
    // 전송 지연이 아니다. 연결마다 종목별 첫 PRICE는 지연 · 간격 측정에서 뺀다.
    const seen = new Set();
    const ws = new WebSocket(WS_URL, null, { tags });

    ws.onopen = () => {
      opened = true;
      wsConnectOk.add(1, tags);
      wsConnectMs.add(Date.now() - t0, tags);
      wsOpened.add(1, tags);
      if (finished) {
        // 여는 사이에 세션이 끝났다. 열린 채로 두면 반복이 끝나지 않는다.
        ws.close();
        return;
      }
      current = ws;
      if (subscribed.size > 0) ws.send(JSON.stringify({ type: 'SUBSCRIBE', symbols: Array.from(subscribed) }));
      ping = setInterval(() => {
        if (ws.readyState === 1) ws.send('{"type":"PING"}');
      }, PING_MS);
      // make-before-break: 새 연결이 열린 뒤에 옛 연결을 닫는다.
      if (previous) previous.close();
    };

    ws.onmessage = (e) => {
      let m;
      try {
        m = JSON.parse(e.data);
      } catch (err) {
        wsErrors.add(1, Object.assign({ where: 'parse' }, tags));
        return;
      }
      wsMsgs.add(1, Object.assign({ type: m.type }, tags));
      if (m.type === 'PRICE') {
        const now = Date.now();
        if (!seen.has(m.symbol) || resubscribed.has(m.symbol)) {
          seen.add(m.symbol);
          resubscribed.delete(m.symbol);
          lastPriceAt[m.symbol] = now;
          return;
        }
        if (Math.random() < LAG_SAMPLE) {
          const ts = parseTs(m.timestamp);
          if (!Number.isNaN(ts)) wsQuoteLag.add(now - ts, tags);
          if (lastPriceAt[m.symbol]) wsQuoteGap.add(now - lastPriceAt[m.symbol], tags);
        }
        lastPriceAt[m.symbol] = now;
      } else if (m.type === 'DRAIN') {
        wsDrain.add(1, tags);
        if (!finished) connect(ws);
      } else if (m.type === 'ERROR') {
        wsErrors.add(1, Object.assign({ where: 'server' }, tags));
      }
    };

    ws.onerror = () => {
      if (!opened) {
        wsConnectOk.add(0, tags);
        wsErrors.add(1, Object.assign({ where: 'connect' }, tags));
      }
    };

    ws.onclose = () => {
      if (ping) clearInterval(ping);
      if (opened) wsClosed.add(1, tags);
      // 내가 닫은 것(세션 끝 · DRAIN 이동)이 아니면 브라우저처럼 다시 붙는다.
      if (!finished && current === ws && Date.now() - started < durationMs) {
        current = null;
        timeouts.push(setTimeout(() => connect(null), 500 + Math.random() * 1000));
      }
    };
  }

  function end() {
    if (finished) return;
    finished = true;
    intervals.forEach((t) => clearInterval(t));
    timeouts.forEach((t) => clearTimeout(t));
    if (onEnd) onEnd();
    if (current) current.close();
  }

  connect(null);
  for (const a of every) intervals.push(setInterval(() => !finished && a.fn(ctl), a.ms));
  timeouts.push(setTimeout(end, durationMs));
  return ctl;
}
