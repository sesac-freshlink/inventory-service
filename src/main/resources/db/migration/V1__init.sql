CREATE TABLE product (
                         id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '상품 ID. 쪽지 productId(Long)',
                         product_code  VARCHAR(20)  NOT NULL COMMENT '상품 코드 "P001". INBOUND 쪽지가 이 코드로 가리킴',
                         name          VARCHAR(100) NOT NULL COMMENT '상품명. RESERVED productName (common v0.1.1)',
                         category      VARCHAR(30)  NOT NULL COMMENT '분류(화면용)',
                         price         BIGINT       NOT NULL COMMENT '단가(원). 전 점포 동일가. RESERVED unitPrice(long)',
                         PRIMARY KEY (id),
                         UNIQUE KEY uk_product_code (product_code)
) COMMENT='상품. 더미 30개 고정, 등록 API 없음';


CREATE TABLE inventory_lot (
                               id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'lot ID. 쪽지 lotId(Long)',
                               store_id          BIGINT      NOT NULL COMMENT '점포 ID = member.store.id (숫자, FK 없음)',
                               product_id        BIGINT      NOT NULL COMMENT '상품 ID → product.id',
                               expiry_date       DATETIME    NOT NULL COMMENT '유통기한. 날짜만 오면 23:59:59 KST',
                               quantity          INT         NOT NULL DEFAULT 0 COMMENT '실물 수량. 입고 +, 판매 확정 −, 폐기 −',
                               reserved_quantity INT         NOT NULL DEFAULT 0 COMMENT '결제 대기(예약) 수량',
                               status            VARCHAR(10) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL / IMMINENT / EXPIRED. 표시용, 스케줄러 갱신',
                               created_at        DATETIME(3) NOT NULL COMMENT 'JPA Auditing',
                               updated_at        DATETIME(3) NOT NULL COMMENT 'JPA Auditing',
                               PRIMARY KEY (id),
                               UNIQUE KEY uk_lot_store_product_expiry (store_id, product_id, expiry_date),
                               KEY idx_lot_store_product_expiry (store_id, product_id, expiry_date),
                               KEY idx_lot_store_expiry (store_id, expiry_date),
                               CONSTRAINT chk_lot_qty CHECK (quantity >= 0),
                               CONSTRAINT chk_lot_reserved CHECK (reserved_quantity >= 0 AND reserved_quantity <= quantity)
) COMMENT='유통기한별 재고 묶음(lot). 구매 가능 = Σ 미경과 lot (quantity − reserved_quantity)';


CREATE TABLE inbound (
                         id           BIGINT      NOT NULL AUTO_INCREMENT,
                         shipment_id  VARCHAR(64) NOT NULL COMMENT 'INBOUND 쪽지 shipmentId. 같은 입고 두 번 방지',
                         store_id     BIGINT      NOT NULL COMMENT '점포 ID = member.store.id',
                         received_at  DATETIME(3) NOT NULL COMMENT '처리 시각',
                         PRIMARY KEY (id),
                         UNIQUE KEY uk_inbound_shipment (shipment_id)
) COMMENT='입고 기록. 멱등 방어(S-20)';


CREATE TABLE reservation (
                             id          BIGINT      NOT NULL AUTO_INCREMENT,
                             order_id    BIGINT      NOT NULL COMMENT '주문 ID = order.orders.id (쪽지 orderId Long, FK 없음. order MVP 명세로 확인)',
                             lot_id      BIGINT      NOT NULL COMMENT '어느 lot에서 잡았는지 → inventory_lot.id',
                             quantity    INT         NOT NULL COMMENT '잡은 수량',
                             status      VARCHAR(10) NOT NULL COMMENT 'RESERVED / CONFIRMED / RELEASED',
                             created_at  DATETIME(3) NOT NULL COMMENT 'JPA Auditing',
                             updated_at  DATETIME(3) NOT NULL COMMENT 'JPA Auditing',
                             PRIMARY KEY (id),
                             UNIQUE KEY uk_reservation_order_lot (order_id, lot_id),
                             KEY idx_reservation_order (order_id),
                             KEY idx_reservation_lot (lot_id),
                             CONSTRAINT chk_reservation_qty CHECK (quantity > 0)
) COMMENT='재고 예약(결제 대기 동안 잡아 둔 수량). 점주 S-10 "예약 수량"에만 노출, 고객 화면 비노출';


CREATE TABLE disposal (
                          id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '폐기 요청 ID. 쪽지 disposalId(Long), S-32 202 응답',
                          store_id      BIGINT      NOT NULL COMMENT '점포 ID = member.store.id',
                          lot_id        BIGINT      NOT NULL COMMENT '폐기 대상 lot → inventory_lot.id',
                          quantity      INT         NOT NULL COMMENT '요청 수량',
                          requested_by  VARCHAR(36) NOT NULL COMMENT '점주 Cognito sub (X-User-Id). 기획안 3-6 VARCHAR(36)',
                          status        VARCHAR(10) NOT NULL DEFAULT 'REQUESTED' COMMENT 'REQUESTED → DONE / SKIPPED(처리 때 수량 부족)',
                          created_at    DATETIME(3) NOT NULL COMMENT '요청 시각',
                          processed_at  DATETIME(3) NULL COMMENT 'DISPOSE 쪽지 처리 시각',
                          PRIMARY KEY (id),
                          KEY idx_disposal_store_created (store_id, created_at),
                          CONSTRAINT chk_disposal_qty CHECK (quantity > 0)
) COMMENT='폐기 요청. POST /owner/disposals → disposal(REQUESTED) + DISPOSE 쪽지 → 처리 후 DONE';


CREATE TABLE ledger (
                        id          BIGINT      NOT NULL AUTO_INCREMENT,
                        store_id    BIGINT      NOT NULL COMMENT '점포 ID = member.store.id',
                        lot_id      BIGINT      NOT NULL COMMENT '변동된 lot → inventory_lot.id. 상품명은 lot → product',
                        type        VARCHAR(10) NOT NULL COMMENT 'INBOUND / SALE / DISPOSE',
                        quantity    INT         NOT NULL COMMENT '부호 포함. 입고 +10, 판매 −1, 폐기 −3. lot별 합계 = lot.quantity',
                        ref_id      VARCHAR(64) NULL COMMENT '입고 shipmentId / 판매 orderId / 폐기 disposalId',
                        created_at  DATETIME(3) NOT NULL COMMENT '변동 시각',
                        PRIMARY KEY (id),
                        KEY idx_ledger_store_created (store_id, created_at),
                        KEY idx_ledger_lot (lot_id)
) COMMENT='재고 변동 원장(S-13). 예약·해제는 기록하지 않음(실물 불변)';


CREATE TABLE shedlock (
                          name       VARCHAR(64)  NOT NULL PRIMARY KEY,
                          lock_until TIMESTAMP(3) NOT NULL,
                          locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                          locked_by  VARCHAR(255) NOT NULL
);