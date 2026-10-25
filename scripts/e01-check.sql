-- E-01 동시 예약 시험 결과 확인 (T-22). 읽기만 한다.
-- scripts/e01-reserve-100.ps1로 보낸 뒤 실행한다. 절차는 docs/동시성-테스트-절차.md.
-- 아래 여섯 값을 시험에 맞춘다. orderId 범위는 스크립트가 끝에 찍어 준다(기본 900001~900100).
-- @outbox_from·@reservation_from은 투입 직전에 받은 outbox·reservation의 마지막 id다(절차 문서 4장).
-- 정리 SQL은 outbox를 지우지 않으므로, 이 값이 없으면 같은 orderId 범위로 다시 시험할 때 이전 결과가 섞인다.

SET @store_id   = 1;
SET @product_id = 15;
SET @order_from = 900001;
SET @order_to   = 900100;
SET @outbox_from = 0;
SET @reservation_from = 0;

-- 1. lot별 수량. 예약 성공 수 = 처음 재고 N이면 reserved_quantity 합이 N이다
SELECT id                              AS lot_id,
       expiry_date,
       quantity,
       reserved_quantity,
       quantity - reserved_quantity    AS available
FROM inventory_lot
WHERE store_id = @store_id
  AND product_id = @product_id
ORDER BY expiry_date, id;

-- 2. 시험 주문의 예약 상태별 건수 (lot 두 개에 걸친 주문은 행이 2개라 주문 수는 DISTINCT로 센다)
SELECT r.status,
       COUNT(DISTINCT r.order_id) AS orders,
       COUNT(*)                   AS reservation_rows,
       SUM(r.quantity)            AS quantity
FROM reservation r
JOIN inventory_lot l ON l.id = r.lot_id
WHERE r.id > @reservation_from
  AND r.order_id BETWEEN @order_from AND @order_to
  AND l.store_id = @store_id
  AND l.product_id = @product_id
GROUP BY r.status;

-- 3. 결과 쪽지 건수와 처리 시간. created_at은 쪽지를 처리한 트랜잭션의 DB 시각이다
--    성공 수 = RESERVED 주문 수, 실패 수 = RESERVE_FAILED 주문 수, 둘의 합 = 보낸 건수
SELECT message_type,
       COUNT(*)                                                         AS messages,
       COUNT(DISTINCT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.orderId'))) AS orders,
       MIN(created_at)                                                  AS first_at,
       MAX(created_at)                                                  AS last_at
FROM outbox
WHERE message_type IN ('RESERVED', 'RESERVE_FAILED')
  AND id > @outbox_from
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.orderId')) AS UNSIGNED) BETWEEN @order_from AND @order_to
GROUP BY message_type;

-- 4. 첫 결과부터 마지막 결과까지 걸린 시간(ms)
SELECT COUNT(*)                                                      AS results,
       MIN(created_at)                                               AS first_at,
       MAX(created_at)                                               AS last_at,
       TIMESTAMPDIFF(MICROSECOND, MIN(created_at), MAX(created_at)) / 1000 AS elapsed_ms
FROM outbox
WHERE message_type IN ('RESERVED', 'RESERVE_FAILED')
  AND id > @outbox_from
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.orderId')) AS UNSIGNED) BETWEEN @order_from AND @order_to;

-- 5. 실패 사유별 건수 (재고 부족이면 OUT_OF_STOCK, availableQuantity는 0이어야 한다)
SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reason'))                    AS reason,
       COUNT(*)                                                           AS messages,
       MAX(CAST(JSON_EXTRACT(payload, '$.availableQuantity') AS SIGNED)) AS max_available
FROM outbox
WHERE message_type = 'RESERVE_FAILED'
  AND id > @outbox_from
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.orderId')) AS UNSIGNED) BETWEEN @order_from AND @order_to
GROUP BY reason;

-- 6. 불변식: 예약 합 <= 실물 합 (ok = 1)
SELECT SUM(quantity)                           AS total_quantity,
       SUM(reserved_quantity)                  AS total_reserved,
       SUM(reserved_quantity) <= SUM(quantity) AS ok
FROM inventory_lot
WHERE store_id = @store_id
  AND product_id = @product_id;

-- 7. 음수·초과 lot (CHECK 제약이 막지만 눈으로도 확인한다. 0행이어야 한다)
SELECT id, quantity, reserved_quantity
FROM inventory_lot
WHERE store_id = @store_id
  AND (quantity < 0 OR reserved_quantity < 0 OR reserved_quantity > quantity);

-- 8. 예약 표와 lot 예약 수량이 맞는지 (이 상품에 시험 주문 말고 다른 예약이 없을 때 diff = 0)
SELECT (SELECT COALESCE(SUM(reserved_quantity), 0)
        FROM inventory_lot
        WHERE store_id = @store_id AND product_id = @product_id) AS lot_reserved,
       (SELECT COALESCE(SUM(r.quantity), 0)
        FROM reservation r
        JOIN inventory_lot l ON l.id = r.lot_id
        WHERE l.store_id = @store_id
          AND l.product_id = @product_id
          AND r.status = 'RESERVED')                              AS reservation_reserved,
       (SELECT COALESCE(SUM(reserved_quantity), 0)
        FROM inventory_lot
        WHERE store_id = @store_id AND product_id = @product_id)
     - (SELECT COALESCE(SUM(r.quantity), 0)
        FROM reservation r
        JOIN inventory_lot l ON l.id = r.lot_id
        WHERE l.store_id = @store_id
          AND l.product_id = @product_id
          AND r.status = 'RESERVED')                              AS diff;
