// k6 쪽에서 직접 세는 지표. 서버 지표(wts.ws.* gauge, 계획서 §6.5 #1)가 아직 없을 때도
// 연결 수 · 수신 메시지 · 지연을 볼 수 있게 한다. Prometheus remote write로 나가면 이름 앞에 k6_가 붙는다
// (Counter는 k6_<이름>_total).
import { Counter, Rate, Trend } from 'k6/metrics';

// WS
export const wsConnectOk = new Rate('ws_connect_ok'); // 연결 성공률
export const wsConnectMs = new Trend('ws_connect_ms', true); // 핸드셰이크 시간
export const wsOpened = new Counter('ws_opened'); // 열린 연결 수(누적). 열린 수 - 닫힌 수 = 지금 연결 수
export const wsClosed = new Counter('ws_closed');
export const wsMsgs = new Counter('ws_msgs'); // 받은 메시지 수, tag type=PRICE|ORDERBOOK|...
export const wsQuoteLag = new Trend('ws_quote_lag_ms', true); // 받은 시각 - 시세 timestamp (표본)
export const wsQuoteGap = new Trend('ws_quote_gap_ms', true); // 같은 종목 PRICE 사이 간격 (표본)
export const wsDrain = new Counter('ws_drain'); // 서버가 보낸 DRAIN(계획된 종료) 수
export const wsErrors = new Counter('ws_errors'); // tag where=connect|server|parse

// 주문
export const orderBizReject = new Counter('order_biz_reject'); // INSUFFICIENT_* (업무 거절, 오류에서 뺀다)
export const orderPriceMissing = new Counter('order_price_missing'); // 503 MARKET_PRICE_* (keeper 없음 = 스크립트 문제)

// 세션(페르소나)
export const sessionsStarted = new Counter('sessions_started'); // tag persona
