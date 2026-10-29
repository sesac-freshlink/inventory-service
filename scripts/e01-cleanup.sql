-- E-01 동시 예약 시험 정리 (T-22). 시험 주문의 예약만 지우고 lot의 reserved_quantity를 되돌린다.
-- 조건 없는 DELETE·TRUNCATE는 쓰지 않는다. 결과를 캡처한 뒤(e01-check.sql) 실행한다.
-- 시험 주문이 확정(CONFIRMED)된 경우는 원장 SALE이 같이 남으므로 이 스크립트로 지우지 않는다.

SET @store_id   = 1;
SET @order_from = 900001;
SET @order_to   = 900100;
-- 투입 직전에 받은 reservation 마지막 id(절차 문서 4장). 이번 투입으로 생긴 예약만 건드린다
SET @reservation_from = 0;

-- 0. 지울 대상 미리 보기
SELECT r.status, COUNT(*) AS reservation_rows, SUM(r.quantity) AS quantity
FROM reservation r
JOIN inventory_lot l ON l.id = r.lot_id
WHERE r.id > @reservation_from
  AND r.order_id BETWEEN @order_from AND @order_to
  AND l.store_id = @store_id
GROUP BY r.status;

START TRANSACTION;

-- 1. 아직 RESERVED인 시험 예약만큼 lot 예약 수량을 되돌린다
UPDATE inventory_lot l
JOIN (
    SELECT lot_id, SUM(quantity) AS reserved
    FROM reservation
    WHERE id > @reservation_from
      AND order_id BETWEEN @order_from AND @order_to
      AND status = 'RESERVED'
    GROUP BY lot_id
) r ON r.lot_id = l.id
SET l.reserved_quantity = l.reserved_quantity - r.reserved,
    l.updated_at        = NOW(3)
WHERE l.store_id = @store_id;

-- 1-1. 고객용 재고 조회 표(store_product_stock)도 같은 규칙으로 다시 계산한다.
--      lot을 SQL로 직접 고치면 조회 표가 따라오지 않는다(앱은 StockChangePublisher가 같은 트랜잭션에서 한다)
UPDATE store_product_stock s
JOIN (
    SELECT store_id,
           product_id,
           COALESCE(SUM(CASE WHEN expiry_date > CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')
                             THEN quantity - reserved_quantity ELSE 0 END), 0) AS available_quantity,
           MIN(CASE WHEN expiry_date > CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')
                     AND quantity > reserved_quantity THEN expiry_date END) AS valid_until
    FROM inventory_lot
    WHERE store_id = @store_id
    GROUP BY store_id, product_id
) l ON l.store_id = s.store_id AND l.product_id = s.product_id
SET s.available_quantity = l.available_quantity,
    s.valid_until        = l.valid_until,
    s.updated_at         = NOW(3)
WHERE s.store_id = @store_id;

-- 2. 시험 예약 행을 지운다 (RESERVED·RELEASED만. 실물이 바뀐 CONFIRMED는 남긴다)
DELETE r
FROM reservation r
JOIN inventory_lot l ON l.id = r.lot_id
WHERE r.id > @reservation_from
  AND r.order_id BETWEEN @order_from AND @order_to
  AND r.status IN ('RESERVED', 'RELEASED')
  AND l.store_id = @store_id;

-- 3. 확인: 남은 시험 예약 0행(CONFIRMED 제외), 음수 lot 0행
SELECT COUNT(*) AS remaining
FROM reservation r
JOIN inventory_lot l ON l.id = r.lot_id
WHERE r.id > @reservation_from
  AND r.order_id BETWEEN @order_from AND @order_to
  AND r.status <> 'CONFIRMED'
  AND l.store_id = @store_id;

SELECT id, quantity, reserved_quantity
FROM inventory_lot
WHERE store_id = @store_id
  AND (reserved_quantity < 0 OR reserved_quantity > quantity);

-- 위 결과를 보고 직접 한 줄을 실행한다. 맞으면 COMMIT, 이상하면 ROLLBACK
-- COMMIT;
-- ROLLBACK;
