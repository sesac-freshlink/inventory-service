CREATE TABLE safety_stock (
                              id          BIGINT      NOT NULL AUTO_INCREMENT,
                              store_id    BIGINT      NOT NULL COMMENT '점포 ID = member.store.id',
                              product_id  BIGINT      NOT NULL COMMENT '상품 ID → product.id',
                              quantity    INT         NOT NULL COMMENT '안전재고. 구매 가능 수량이 이보다 적으면 S-10 lowStock',
                              created_at  DATETIME(3) NOT NULL COMMENT '처음 정한 시각',
                              updated_at  DATETIME(3) NOT NULL COMMENT '마지막으로 바꾼 시각',
                              PRIMARY KEY (id),
                              UNIQUE KEY uk_safety_stock_store_product (store_id, product_id),
                              CONSTRAINT chk_safety_stock_qty CHECK (quantity >= 0 AND quantity <= 999)
) COMMENT='점포·상품별 안전재고(S-40). 행이 없는 상품은 기본값 5를 쓴다';
