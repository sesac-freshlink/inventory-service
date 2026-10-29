CREATE TABLE store_product_stock (
                                     id                 BIGINT      NOT NULL AUTO_INCREMENT,
                                     store_id           BIGINT      NOT NULL COMMENT '점포 ID = member.store.id',
                                     product_id         BIGINT      NOT NULL COMMENT '상품 ID → product.id',
                                     available_quantity INT         NOT NULL COMMENT '구매 가능 수량 = Σ 미경과 lot (quantity − reserved_quantity)',
                                     valid_until        DATETIME    NULL     COMMENT '이 값이 맞는 마지막 시각 = 남은 재고가 있는 미경과 lot 중 가장 빠른 유통기한. 이 시각부터는 품절로 본다. NULL이면 남은 재고 없음',
                                     updated_at         DATETIME(3) NOT NULL COMMENT '마지막으로 다시 계산한 시각',
                                     PRIMARY KEY (id),
                                     UNIQUE KEY uk_store_product_stock (store_id, product_id),
                                     KEY idx_store_product_stock_valid_until (valid_until),
                                     CONSTRAINT chk_store_product_stock_qty CHECK (available_quantity >= 0)
) COMMENT='고객용 재고 조회 표(CQRS). 재고를 바꾸는 처리가 같은 트랜잭션에서 다시 계산해 넣고, 고객 상품 목록(U-10)은 이 표만 읽는다';

-- 이미 들어 있는 lot으로 미리 채운다. 빈 표로 시작하면 dev·prod의 고객 목록이 전부 품절로 보인다.
-- lot 기한은 한국 시각으로 저장되어 있으므로 지금 시각도 한국 시각으로 바꿔 비교한다(DB 시간대 설정과 상관없다).
-- 계산 규칙은 StockChangePublisher와 같다. 이미 있는 행은 건드리지 않는다(같은 문장을 테스트에서 다시 돌린다).
INSERT INTO store_product_stock (store_id, product_id, available_quantity, valid_until, updated_at)
SELECT store_id,
       product_id,
       COALESCE(SUM(CASE WHEN expiry_date > CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00') THEN quantity - reserved_quantity ELSE 0 END), 0),
       MIN(CASE WHEN expiry_date > CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00') AND quantity > reserved_quantity THEN expiry_date END),
       NOW(3)
FROM inventory_lot
GROUP BY store_id, product_id
ON DUPLICATE KEY UPDATE available_quantity = available_quantity;
