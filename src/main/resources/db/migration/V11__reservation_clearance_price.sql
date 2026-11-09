-- 마감세일 함께 사기. 예약 때 정한 마감세일 단가를 남겨, 같은 주문의 RESERVE가 다시 와도 처음과 같은 RESERVED를 보낸다.
-- 시간이 지나면 기본 lot도 24시간 안으로 들어오므로 lot 기한으로 다시 계산하면 처음과 달라진다.
ALTER TABLE reservation
    ADD COLUMN clearance_unit_price BIGINT NULL COMMENT '마감세일 lot에서 잡았으면 그때의 단가(원), 기본 lot이면 NULL';
