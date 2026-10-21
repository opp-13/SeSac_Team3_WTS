-- 정합성 검증(계획서 §7.12). 스키마: trading-service db/migration V1~V3.
-- 시험 계정만이 아니라 모든 계좌의 불변식을 본다(어느 계좌에서든 깨지면 버그다). bad는 모두 0이어야 한다.

SELECT '1 예수금 음수 또는 예약금 > 예수금' AS chk, COUNT(*) AS bad
FROM virtual_accounts
WHERE cash_balance < 0 OR reserved_cash < 0 OR reserved_cash > cash_balance;

SELECT '2 같은 계좌 · 같은 Idempotency-Key 중복 주문' AS chk, COUNT(*) AS bad
FROM (SELECT account_id, idempotency_key FROM orders GROUP BY account_id, idempotency_key HAVING COUNT(*) > 1) d;

SELECT '3 원장 합계 != 예수금' AS chk, COUNT(*) AS bad
FROM virtual_accounts a
LEFT JOIN (SELECT account_id, SUM(amount) AS s FROM ledger_entries GROUP BY account_id) l ON l.account_id = a.id
WHERE COALESCE(l.s, 0) <> a.cash_balance;

SELECT '4 보유 음수 또는 예약 수량 > 보유' AS chk, COUNT(*) AS bad
FROM positions
WHERE quantity < 0 OR reserved_quantity < 0 OR reserved_quantity > quantity;

SELECT '5 FILLED인데 체결 없음' AS chk, COUNT(*) AS bad
FROM orders o
WHERE o.status = 'FILLED' AND NOT EXISTS (SELECT 1 FROM executions e WHERE e.order_id = o.id);

SELECT '6 체결 수 != 주문당 1건(부분 체결 없음)' AS chk, COUNT(*) AS bad
FROM (SELECT order_id FROM executions GROUP BY order_id HAVING COUNT(*) <> 1) d;

SELECT '7 1분 넘게 ACCEPTED로 남은 시장가' AS chk, COUNT(*) AS bad
FROM orders
WHERE order_type = 'MARKET' AND status = 'ACCEPTED' AND created_at < UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE;

-- 참고(0이 아니어도 된다): 상태별 주문 수, 미발행 outbox
SELECT status, order_type, COUNT(*) AS orders FROM orders GROUP BY status, order_type ORDER BY status, order_type;
SELECT status, COUNT(*) AS outbox FROM outbox_events GROUP BY status;
