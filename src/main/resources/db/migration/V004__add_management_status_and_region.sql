-- 주문 관리상태 및 지역명 컬럼 추가
-- management_status: 확인필요(초기값), 확정, 대기, 이월, 불참, 환불(대기자환불), 환불(참가취소,변심)
-- region_name: 상품코드 기반 지역명

ALTER TABLE sync_orders
    ADD COLUMN management_status VARCHAR(50) DEFAULT '확인필요' COMMENT '관리상태 (확인필요/확정/대기/이월/불참/환불)' AFTER opt_preferred_date,
    ADD COLUMN region_name VARCHAR(50) COMMENT '지역명 (상품코드 기반)' AFTER management_status;

-- 인덱스 추가 (관리상태 및 지역명 기반 필터링 지원)
ALTER TABLE sync_orders
    ADD INDEX idx_management_status (management_status),
    ADD INDEX idx_region_name (region_name);
