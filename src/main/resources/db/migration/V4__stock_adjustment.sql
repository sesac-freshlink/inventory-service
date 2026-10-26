ALTER TABLE inventory_lot
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0
        COMMENT '낙관적 락(S-14). JPA가 고칠 때마다 1 올리고 UPDATE ... WHERE version = 읽은 값으로 겹침을 찾는다';

ALTER TABLE ledger
    MODIFY COLUMN type VARCHAR(10) NOT NULL COMMENT 'INBOUND / SALE / DISPOSE / ADJUST';


CREATE TABLE adjustment (
                            id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '조정 ID. 원장 ADJUST의 ref_id',
                            store_id         BIGINT       NOT NULL COMMENT '점포 ID = member.store.id',
                            lot_id           BIGINT       NOT NULL COMMENT '조정한 lot → inventory_lot.id',
                            delta            INT          NOT NULL COMMENT '증감 수량. 늘리면 +, 줄이면 −',
                            quantity_before  INT          NOT NULL COMMENT '조정 전 lot 실물 수량',
                            quantity_after   INT          NOT NULL COMMENT '조정 뒤 lot 실물 수량',
                            reason           VARCHAR(10)  NOT NULL COMMENT 'COUNT(실사) / DAMAGE(파손) / LOSS(분실) / OTHER',
                            memo             VARCHAR(200) NULL COMMENT '점주 메모',
                            adjusted_by      VARCHAR(36)  NOT NULL COMMENT '점주 Cognito sub (X-User-Id)',
                            created_at       DATETIME(3)  NOT NULL COMMENT '조정 시각',
                            PRIMARY KEY (id),
                            KEY idx_adjustment_store_created (store_id, created_at),
                            CONSTRAINT chk_adjustment_delta CHECK (delta <> 0),
                            CONSTRAINT chk_adjustment_after CHECK (quantity_after = quantity_before + delta AND quantity_after >= 0)
) COMMENT='재고 수동 조정·실사(S-14). 쪽지를 거치지 않는 유일한 재고 변경이라 inventory_lot.version으로 겹침을 막는다';
