-- 마감세일(11/6): 고객용 재고 조회 표에 마감세일 몫을 더한다.
--  available_quantity는 그대로 "미경과 lot 전체"의 (수량 − 예약) 합이다.
--  clearance_quantity  = 그중 24시간 안에 끝나는 lot의 합(마감세일 몫). 기본 몫 = available − clearance
--  normal_expiry       = 24시간 밖 lot 중 남은 재고가 있는 가장 빠른 기한(기본 주문으로 먼저 받는 기한)
--  clearance_expiry    = 24시간 안 lot 중 남은 재고가 있는 가장 빠른 기한(마감세일로 먼저 받는 기한)
--  valid_until은 "값이 바뀌는 다음 시각"으로 넓힌다: lot이 기한을 넘는 시각과 lot이 24시간 안으로 들어오는 시각 중 빠른 쪽.
ALTER TABLE store_product_stock
    ADD COLUMN clearance_quantity INT      NOT NULL DEFAULT 0 COMMENT '24시간 안에 끝나는 lot의 (수량 − 예약) 합. 마감세일 몫' AFTER available_quantity,
    ADD COLUMN normal_expiry      DATETIME NULL COMMENT '24시간 밖 lot 중 남은 재고가 있는 가장 빠른 기한' AFTER clearance_quantity,
    ADD COLUMN clearance_expiry   DATETIME NULL COMMENT '24시간 안 lot 중 남은 재고가 있는 가장 빠른 기한' AFTER normal_expiry,
    ADD CONSTRAINT chk_store_product_stock_clearance CHECK (clearance_quantity >= 0 AND clearance_quantity <= available_quantity);

-- 이미 있는 행을 지금 lot으로 다시 채운다. 계산 규칙은 StockChangePublisher와 같다. lot 기한은 한국 시각이라 지금도 한국 시각으로 비교한다
UPDATE store_product_stock s
    JOIN (
        SELECT l.store_id,
               l.product_id,
               COALESCE(SUM(CASE WHEN l.expiry_date > t.n AND l.expiry_date <= t.n + INTERVAL 24 HOUR
                                 THEN l.quantity - l.reserved_quantity ELSE 0 END), 0) AS clearance_quantity,
               MIN(CASE WHEN l.expiry_date > t.n + INTERVAL 24 HOUR AND l.quantity > l.reserved_quantity
                        THEN l.expiry_date END) AS normal_expiry,
               MIN(CASE WHEN l.expiry_date > t.n AND l.expiry_date <= t.n + INTERVAL 24 HOUR AND l.quantity > l.reserved_quantity
                        THEN l.expiry_date END) AS clearance_expiry,
               MIN(CASE WHEN l.expiry_date > t.n + INTERVAL 24 HOUR AND l.quantity > l.reserved_quantity
                        THEN l.expiry_date - INTERVAL 24 HOUR END) AS enters_clearance
        FROM inventory_lot l
                 CROSS JOIN (SELECT CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00') AS n) t
        GROUP BY l.store_id, l.product_id
    ) a ON a.store_id = s.store_id AND a.product_id = s.product_id
SET s.clearance_quantity = LEAST(a.clearance_quantity, s.available_quantity),
    s.normal_expiry      = a.normal_expiry,
    s.clearance_expiry   = a.clearance_expiry,
    s.valid_until        = CASE
                               WHEN s.valid_until IS NULL THEN a.enters_clearance
                               WHEN a.enters_clearance IS NULL THEN s.valid_until
                               ELSE LEAST(s.valid_until, a.enters_clearance)
                           END;
