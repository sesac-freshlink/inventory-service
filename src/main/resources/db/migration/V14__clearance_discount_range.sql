-- 마감세일 할인율 범위를 1~95%에서 10~90%로 좁힌다(11/17). V13은 이미 적용돼 고치지 않고 새 버전으로 바꾼다.
-- 범위 밖으로 저장된 행이 있으면 가까운 끝값으로 맞춘 뒤 제약을 바꾼다(조건 있는 UPDATE)
UPDATE clearance_setting
SET discount_percent = LEAST(GREATEST(discount_percent, 10), 90)
WHERE discount_percent < 10 OR discount_percent > 90;

ALTER TABLE clearance_setting DROP CHECK chk_clearance_setting_discount;
ALTER TABLE clearance_setting
    ADD CONSTRAINT chk_clearance_setting_discount CHECK (discount_percent >= 10 AND discount_percent <= 90);
