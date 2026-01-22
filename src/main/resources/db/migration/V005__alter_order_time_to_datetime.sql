-- order_time 컬럼을 VARCHAR에서 DATETIME으로 변경
-- API 응답은 UTC 시간이며, 앱에서 KST로 변환하여 저장

-- 1. 기존 컬럼 삭제 (데이터 손실 - 재동기화 필요)
ALTER TABLE sync_orders DROP COLUMN order_time;

-- 2. DATETIME 타입으로 새 컬럼 추가
ALTER TABLE sync_orders ADD COLUMN order_time DATETIME COMMENT '주문일시 (KST)' AFTER region_name;

-- 3. 인덱스 생성
CREATE INDEX idx_order_time ON sync_orders (order_time);
