-- 상품 이미지(U-10·S-10). 값은 CU 편의점 사이트(cu.bgfretail.com)의 상품 이미지 주소다. 시연용이며 출처는 README에 적는다.
-- 비어 있어도 되는 컬럼이라 infra의 seed(dev-inventory.sql)는 그대로 둔다. 새 상품은 NULL로 두면 화면이 자리표시를 보여 준다.
ALTER TABLE product
    ADD COLUMN image_url VARCHAR(300) NULL COMMENT '상품 이미지 주소. 없으면 화면이 자리표시를 보여 준다';

-- 더미 상품 30개의 이미지. 상품 코드 기준이라 id가 달라도 맞는다. 이름이 CU에 없는 상품은 같은 종류의 비슷한 상품 이미지다.
UPDATE product SET image_url = CASE product_code
    WHEN 'P001' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8809196622070.jpg'  -- 제육볶음 도시락 ← 도)제육볶음돈까스더블
    WHEN 'P002' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8800361441359.jpg'  -- 치킨마요 도시락 ← 햄)치킨마요롱버거
    WHEN 'P003' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8800387752798.jpg'  -- 불고기 도시락 ← 도)뚝배기불고기정식
    WHEN 'P004' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8800279675761_2.png'  -- 참치마요 삼각김밥 ← 삼)참치마요삼각
    WHEN 'P005' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8800336397827_1.png'  -- 전주비빔 삼각김밥 ← 삼)전주비빔고추장삼각
    WHEN 'P006' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8800336395403_1.png'  -- 참치 김밥 ← 김)참치김밥
    WHEN 'P007' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8800271905354_1.png'  -- 햄치즈 샌드위치 ← 샌)햄치즈샌드
    WHEN 'P008' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8800387751241.jpg'  -- 에그마요 샌드위치 ← 샌)스팸계란마요샌드
    WHEN 'P009' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8800336394567_1.png'  -- 불고기 버거 ← 햄)더블치즈불고기버거
    WHEN 'P010' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8809148599009.jpg'  -- 닭가슴살 샐러드 ← 샐)오리지널닭가슴살샐러
    WHEN 'P011' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8809148599016.png'  -- 리코타치즈 샐러드 ← 샐)허니리코타치즈샐러드
    WHEN 'P012' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8801115118169.jpg'  -- 흰 우유 500ml ← 서울)저지방우유1L
    WHEN 'P013' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/0000088002798.png'  -- 바나나 우유 240ml ← 빙그레)바나나우유240ml
    WHEN 'P014' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8809520491549.jpg'  -- 플레인 요거트 400g ← 요즘)마시는그릭요거트750
    WHEN 'P015' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8801045823898.jpg'  -- 매운 컵라면 115g ← 오뚜기)진라면약매큰컵
    WHEN 'P016' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8801045643359.jpg'  -- 순한 컵라면 110g ← 오뚜기)진라면약매소컵
    WHEN 'P017' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8801068091632_1.png'  -- 단팥빵 ← 삼립)정통단팥빵
    WHEN 'P018' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8809895798366_2.png'  -- 소보로빵 ← 405)피넛크림소보로빵
    WHEN 'P019' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8801065003188.jpg'  -- 크림치즈 베이글 ← KH)뉴욕베이글플레인
    WHEN 'P020' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8801121289105.jpg'  -- 초코 케이크 조각 ← 유라가)크런치초코떡케익
    WHEN 'P021' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8801115800521.jpg'  -- 우유 푸딩 ← 서울)저지밀크푸딩
    WHEN 'P022' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8809064499933.jpg'  -- 컵 과일 (파인애플) ← 뚜또)파인애플컵
    WHEN 'P023' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8809918461024.jpg'  -- 바나나 2입 ← 백작C)바나나2입
    WHEN 'P024' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8801069422664.jpg'  -- 아메리카노 컵커피 250ml ← PBICK)아메리카노320ml
    WHEN 'P025' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8801056071226.png'  -- 오렌지 주스 350ml ← 롯데)델몬트오렌지P400ml
    WHEN 'P026' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8809387745878.png'  -- 두부 1모 ← 득템)두부300g
    WHEN 'P027' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8801392194115.jpg'  -- 계란 10구 ← CJ)더건강한반숙란2입
    WHEN 'P028' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8809216733885_1.png'  -- 훈제 닭가슴살 100g ← 득템)뉴닭가슴살훈제
    WHEN 'P029' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8809265025221.jpg'  -- 어묵탕 컵 ← 고래사)어묵탕얼큰한맛
    WHEN 'P030' THEN 'https://tqklhszfkvzk6518638.edge.naverncp.com/product/8800336393997.jpg'  -- 떡볶이 컵 ← PBICK)국물밀떡볶이컵
    ELSE image_url END
WHERE product_code IN ('P001', 'P002', 'P003', 'P004', 'P005', 'P006', 'P007', 'P008', 'P009', 'P010', 'P011', 'P012', 'P013', 'P014', 'P015', 'P016', 'P017', 'P018', 'P019', 'P020', 'P021', 'P022', 'P023', 'P024', 'P025', 'P026', 'P027', 'P028', 'P029', 'P030');
