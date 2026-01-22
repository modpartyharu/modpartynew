-- V010: 결제상태 기반 관리상태 개선 및 리얼타임 체크 지원

-- 1. 리얼타임 결제상태 체크 시간 컬럼 추가 (5분 캐싱용)
ALTER TABLE sync_orders
    ADD COLUMN last_realtime_check DATETIME NULL COMMENT '마지막 리얼타임 결제상태 체크 시간';

-- 2. 추가 주문옵션 필드
ALTER TABLE sync_orders
    ADD COLUMN opt_premium VARCHAR(100) NULL COMMENT '프리미엄 옵션 여부',
    ADD COLUMN event_date VARCHAR(100) NULL COMMENT '진행날짜 (이벤트일)';

-- 3. 결제대기중인 주문들의 관리상태를 '결제대기중'으로 업데이트
-- (기존 '확인필요' 상태 중 결제상태가 PAYMENT_PREPARATION인 것들)
UPDATE sync_orders
SET management_status = '결제대기중'
WHERE management_status = '확인필요'
  AND payment_status = 'PAYMENT_PREPARATION';

-- 4. 리얼타임 체크 인덱스
CREATE INDEX idx_sync_orders_realtime_check ON sync_orders (site_code, last_realtime_check);
