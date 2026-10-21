// REST 호출 모음(게이트웨이 경유). 태그 kind=query|order|login 은 thresholds(config.js)가 쓴다.
// 계약: docs/api/openapi.yaml. 주문 body의 가격 필드는 limitPrice, 미체결 상태는 ACCEPTED다.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from './config.js';
import { authParams } from './auth.js';
import { orderBizReject, orderPriceMissing } from './metrics.js';

// Idempotency-Key(1~64자). 주문마다 새로 만든다.
export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function errorCode(res) {
  try {
    return res.json('code') || '';
  } catch (e) {
    return '';
  }
}

// 주문. 201이면 성공, 400 INSUFFICIENT_*은 업무 거절(오류가 아님), 503 MARKET_PRICE_*은 시세가 없다는 뜻
// (그 종목을 아무도 안 보고 있다 → keeper 시나리오가 빠졌거나 종목이 100개를 넘었다).
export function placeOrder(token, { symbol, side, orderType = 'MARKET', quantity = 1, limitPrice, idempotencyKey }) {
  const body = { symbol, side, orderType, quantity };
  if (orderType === 'LIMIT') body.limitPrice = limitPrice;
  const res = http.post(
    `${BASE_URL}/api/trading/orders`,
    JSON.stringify(body),
    authParams(token, 'order', 'POST /api/trading/orders', {
      headers: { 'Idempotency-Key': idempotencyKey || uuid() },
      tags: { side, orderType },
      responseCallback: http.expectedStatuses(201, 400),
    }),
  );
  const code = res.status === 201 ? '' : errorCode(res);
  if (res.status === 400 && code.startsWith('INSUFFICIENT_')) orderBizReject.add(1, { code });
  if (res.status === 503 && code.startsWith('MARKET_PRICE_')) orderPriceMissing.add(1, { code });
  check(
    res,
    { 'order 201 or business reject': (r) => r.status === 201 || (r.status === 400 && code.startsWith('INSUFFICIENT_')) },
    { kind: 'order' },
  );
  return res.status === 201 ? res.json() : null;
}

export function cancelOrder(token, orderId) {
  return http.del(
    `${BASE_URL}/api/trading/orders/${orderId}`,
    null,
    authParams(token, 'order', 'DELETE /api/trading/orders/{id}', { responseCallback: http.expectedStatuses(200, 409) }),
  );
}

export function pendingOrders(token) {
  const res = http.get(
    `${BASE_URL}/api/trading/orders?status=ACCEPTED`,
    authParams(token, 'query', 'GET /api/trading/orders?status=ACCEPTED'),
  );
  return res.status === 200 ? res.json() : [];
}

export function portfolio(token) {
  return http.get(`${BASE_URL}/api/trading/portfolio`, authParams(token, 'query', 'GET /api/trading/portfolio'));
}

export function account(token) {
  return http.get(`${BASE_URL}/api/trading/account`, authParams(token, 'query', 'GET /api/trading/account'));
}

// 관심종목(로그인 사용자의 /wts 왼쪽 목록, frontend useWatchlist)
export function watchlist(token) {
  return http.get(`${BASE_URL}/api/users/me/watchlist`, authParams(token, 'query', 'GET /api/users/me/watchlist'));
}

// 시장 조회(공개, 토큰 없음)
function get(path, name) {
  return http.get(`${BASE_URL}${path}`, { tags: { kind: 'query', name } });
}

export function stockList(page = 0) {
  return get(`/api/market/stocks?page=${page}&size=20`, 'GET /api/market/stocks');
}

export function stockDetail(symbol) {
  return get(`/api/market/stocks/${symbol}`, 'GET /api/market/stocks/{symbol}');
}

export function candles(symbol) {
  return get(`/api/market/stocks/${symbol}/candles?interval=1m&limit=120`, 'GET /api/market/stocks/{symbol}/candles');
}

// 현재가. 지정가 주문 가격을 정할 때 쓴다. 이 호출도 "보는 중"으로 기록된다.
export function price(symbol) {
  const res = get(`/api/market/stocks/${symbol}/price`, 'GET /api/market/stocks/{symbol}/price');
  return res.status === 200 ? Number(res.json('price')) : null;
}
