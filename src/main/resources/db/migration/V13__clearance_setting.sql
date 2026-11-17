CREATE TABLE clearance_setting (
                                   id               BIGINT      NOT NULL AUTO_INCREMENT,
                                   store_id         BIGINT      NOT NULL COMMENT '점포 ID = member.store.id',
                                   category         VARCHAR(30) NOT NULL COMMENT '상품 분류 = product.category',
                                   enabled          BOOLEAN     NOT NULL COMMENT '마감세일 적용. false면 24시간 안 lot도 기본가로 판다',
                                   discount_percent INT         NOT NULL COMMENT '할인율(%). 단가는 10원 단위 내림, 최소 10원',
                                   sale_start_hour  INT         NOT NULL COMMENT '판매 시작 시(이 시 정각부터)',
                                   sale_end_hour    INT         NOT NULL COMMENT '판매 끝 시(이 시 정각 전까지, 24 = 자정)',
                                   created_at       DATETIME(3) NOT NULL,
                                   updated_at       DATETIME(3) NOT NULL,
                                   PRIMARY KEY (id),
                                   UNIQUE KEY uk_clearance_setting_store_category (store_id, category),
                                   CONSTRAINT chk_clearance_setting_discount CHECK (discount_percent >= 1 AND discount_percent <= 95),
                                   CONSTRAINT chk_clearance_setting_hours CHECK (sale_start_hour >= 0 AND sale_end_hour <= 24 AND sale_start_hour < sale_end_hour)
) COMMENT='점주가 분류마다 정한 마감세일 적용 여부·할인율·판매 시간. 행이 없으면 기본값(적용, 30%, 06~23시)';
