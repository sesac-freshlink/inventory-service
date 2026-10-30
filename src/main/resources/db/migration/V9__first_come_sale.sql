CREATE TABLE first_come_sale (
                                 id          BIGINT      NOT NULL AUTO_INCREMENT,
                                 store_id    BIGINT      NOT NULL COMMENT '점포 ID = member.store.id',
                                 product_id  BIGINT      NOT NULL COMMENT '상품 ID → product.id',
                                 sale_limit  INT         NOT NULL COMMENT '선착순 판매 한도. 시작 뒤 예약·확정된 수량이 이를 넘지 않는다',
                                 started_at  DATETIME(3) NOT NULL COMMENT '선착순을 켠 시각. 이 뒤에 생긴 예약만 한도에 센다. 한도를 바꿔도 그대로',
                                 created_at  DATETIME(3) NOT NULL,
                                 updated_at  DATETIME(3) NOT NULL,
                                 PRIMARY KEY (id),
                                 UNIQUE KEY uk_first_come_sale_store_product (store_id, product_id),
                                 CONSTRAINT chk_first_come_sale_limit CHECK (sale_limit >= 1 AND sale_limit <= 999)
) COMMENT='점주가 정한 선착순 상품과 판매 한도. 행이 있으면 선착순(order 대기열이 남은 수량으로 입장 인원을 정한다), 끄면 행을 지운다';
