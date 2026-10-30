CREATE TABLE product_recommendation (
                                        id          BIGINT      NOT NULL AUTO_INCREMENT,
                                        store_id    BIGINT      NOT NULL COMMENT '점포 ID = member.store.id',
                                        product_id  BIGINT      NOT NULL COMMENT '상품 ID → product.id',
                                        created_at  DATETIME(3) NOT NULL COMMENT '점주가 추천으로 정한 시각',
                                        PRIMARY KEY (id),
                                        UNIQUE KEY uk_product_recommendation_store_product (store_id, product_id)
) COMMENT='점주가 고른 추천상품(U-10 고객 목록 "추천상품"). 행이 있으면 추천, 끄면 행을 지운다';
