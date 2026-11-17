-- 상품 브랜드(S-10 점주 재고 목록·추천·선착순 칸). 시연용이라 정확한 제조사가 아니라 그럴듯한 값이다(11/15).
-- 가공식품은 CU 사이트 상품명 앞 "브랜드)"를 따르고, CU에서 직접 만드는 도시락·김밥·샌드위치·샐러드·PB 상품은 CU로 둔다.
-- 비어 있어도 되는 컬럼이라 infra seed(dev·prod-inventory.sql)는 그대로 둔다. 새 상품은 NULL이면 화면이 "-"를 보여 준다.
ALTER TABLE product
    ADD COLUMN brand VARCHAR(30) NULL COMMENT '브랜드(화면용). 없으면 NULL';

-- 상품 코드 기준이라 id가 달라도 맞는다. 목록에 없는 상품은 건드리지 않는다
UPDATE product SET brand = CASE product_code
    WHEN 'P001' THEN 'CU'          -- 제육볶음 도시락
    WHEN 'P002' THEN 'CU'          -- 치킨마요 도시락
    WHEN 'P003' THEN 'CU'          -- 불고기 도시락
    WHEN 'P004' THEN 'CU'          -- 참치마요 삼각김밥
    WHEN 'P005' THEN 'CU'          -- 전주비빔 삼각김밥
    WHEN 'P006' THEN 'CU'          -- 참치 김밥
    WHEN 'P007' THEN 'CU'          -- 햄치즈 샌드위치
    WHEN 'P008' THEN 'CU'          -- 에그마요 샌드위치
    WHEN 'P009' THEN 'CU'          -- 불고기 버거
    WHEN 'P010' THEN 'CU'          -- 닭가슴살 샐러드
    WHEN 'P011' THEN 'CU'          -- 리코타치즈 샐러드
    WHEN 'P012' THEN '서울우유'    -- 흰 우유 500ml ← 서울)저지방우유1L
    WHEN 'P013' THEN '빙그레'      -- 바나나 우유 240ml ← 빙그레)바나나우유240ml
    WHEN 'P014' THEN '매일유업'    -- 플레인 요거트 400g
    WHEN 'P015' THEN '오뚜기'      -- 매운 컵라면 115g ← 오뚜기)진라면약매큰컵
    WHEN 'P016' THEN '오뚜기'      -- 순한 컵라면 110g ← 오뚜기)진라면약매소컵
    WHEN 'P017' THEN '삼립'        -- 단팥빵 ← 삼립)정통단팥빵
    WHEN 'P018' THEN '삼립'        -- 소보로빵
    WHEN 'P019' THEN 'CU'          -- 크림치즈 베이글
    WHEN 'P020' THEN 'CU'          -- 초코 케이크 조각
    WHEN 'P021' THEN '서울우유'    -- 우유 푸딩 ← 서울)저지밀크푸딩
    WHEN 'P022' THEN 'CU'          -- 컵 과일 (파인애플)
    WHEN 'P023' THEN '델몬트'      -- 바나나 2입
    WHEN 'P024' THEN 'CU'          -- 아메리카노 컵커피 250ml ← PBICK(CU PB)
    WHEN 'P025' THEN '델몬트'      -- 오렌지 주스 350ml ← 롯데)델몬트오렌지
    WHEN 'P026' THEN '풀무원'      -- 두부 1모
    WHEN 'P027' THEN 'CJ'          -- 계란 10구 ← CJ)반숙란
    WHEN 'P028' THEN '하림'        -- 훈제 닭가슴살 100g
    WHEN 'P029' THEN '고래사'      -- 어묵탕 컵 ← 고래사)어묵탕
    WHEN 'P030' THEN 'CU'          -- 떡볶이 컵 ← PBICK(CU PB)
    WHEN 'P031' THEN 'CU'          -- 말차 크림 롤케이크
    WHEN 'P032' THEN '빙그레'      -- 딸기 요거트 스무디 300ml
    WHEN 'P033' THEN '삼립'        -- 갈릭 버터 바게트
    WHEN 'P034' THEN '농심'        -- 마라 볶음면 컵
    WHEN 'P035' THEN '매일유업'    -- 고구마 라떼 250ml
    WHEN 'P036' THEN 'CJ'          -- 김치 치즈 볶음밥
END
WHERE product_code IN ('P001', 'P002', 'P003', 'P004', 'P005', 'P006', 'P007', 'P008', 'P009', 'P010',
                       'P011', 'P012', 'P013', 'P014', 'P015', 'P016', 'P017', 'P018', 'P019', 'P020',
                       'P021', 'P022', 'P023', 'P024', 'P025', 'P026', 'P027', 'P028', 'P029', 'P030',
                       'P031', 'P032', 'P033', 'P034', 'P035', 'P036');
