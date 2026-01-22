-- 참여날짜 정렬을 위한 DateTime 필드 추가
-- 기존 opt_preferred_date(문자열)를 변환하지 않고 별도 필드로 추가

ALTER TABLE sync_orders
ADD COLUMN order_event_date_dt DATETIME NULL COMMENT '참여날짜 DateTime (정렬용)' AFTER opt_preferred_date;

-- 인덱스 추가 (정렬 성능 최적화)
CREATE INDEX idx_order_event_date_dt ON sync_orders(order_event_date_dt);
