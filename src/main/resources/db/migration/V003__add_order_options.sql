-- 주문옵션 정보 컬럼 추가 (optionInfo에서 파싱한 값들)
-- 주문 시 입력한 정보가 회원 프로필보다 더 신뢰성 있음

ALTER TABLE sync_orders
    ADD COLUMN opt_gender VARCHAR(10) COMMENT '주문옵션-성별 (남/여)' AFTER all_products,
    ADD COLUMN opt_birth_year VARCHAR(4) COMMENT '주문옵션-출생년도 (1989 등)' AFTER opt_gender,
    ADD COLUMN opt_age INT COMMENT '주문옵션-나이 (현재년도-출생년도)' AFTER opt_birth_year,
    ADD COLUMN opt_job VARCHAR(200) COMMENT '주문옵션-직업_회사' AFTER opt_age,
    ADD COLUMN opt_preferred_date VARCHAR(100) COMMENT '주문옵션-참여희망날짜' AFTER opt_job;

-- 인덱스 추가 (성별/나이 기반 필터링 지원)
ALTER TABLE sync_orders
    ADD INDEX idx_opt_gender (opt_gender),
    ADD INDEX idx_opt_age (opt_age);
