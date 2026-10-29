CREATE TABLE product_memo (
                              id          BIGINT       NOT NULL AUTO_INCREMENT,
                              store_id    BIGINT       NOT NULL COMMENT '점포 ID = member.store.id',
                              product_id  BIGINT       NOT NULL COMMENT '상품 ID → product.id',
                              content     VARCHAR(200) NOT NULL COMMENT '메모 내용. 빈 메모는 저장하지 않고 지운다',
                              version     BIGINT       NOT NULL DEFAULT 0 COMMENT '낙관적 락. 화면이 읽은 version과 다르면 409 MEMO_CHANGED',
                              created_at  DATETIME(3)  NOT NULL COMMENT '처음 저장한 시각',
                              updated_at  DATETIME(3)  NOT NULL COMMENT '마지막으로 고친 시각',
                              PRIMARY KEY (id),
                              UNIQUE KEY uk_product_memo_store_product (store_id, product_id),
                              KEY idx_product_memo_store_updated (store_id, updated_at)
) COMMENT='점주 상품 메모. 점포·상품마다 하나, 처음 저장할 때 생긴다';
