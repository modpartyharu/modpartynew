-- V013: 수동 추가 주문 플래그 추가
-- 수동 추가 주문을 명시적으로 구분하기 위한 플래그 컬럼

ALTER TABLE sync_orders
    ADD COLUMN is_manual_order BOOLEAN DEFAULT FALSE COMMENT '수동 추가 주문 여부' AFTER event_date;

-- 기존 수동 추가 주문 데이터 마이그레이션 (order_status가 NULL이고 order_no가 음수인 경우)
UPDATE sync_orders
SET is_manual_order = TRUE
WHERE (order_status IS NULL OR order_status = '')
  AND order_no < 0;

-- 인덱스 추가
ALTER TABLE sync_orders ADD INDEX idx_is_manual_order (is_manual_order);
