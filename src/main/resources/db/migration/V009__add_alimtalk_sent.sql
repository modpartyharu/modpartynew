-- V009: 알림톡 발송여부 컬럼 추가

-- sync_orders 테이블에 알림톡 발송여부 컬럼 추가
ALTER TABLE sync_orders
    ADD COLUMN alimtalk_sent BOOLEAN NOT NULL DEFAULT FALSE COMMENT '알림톡 발송여부',
    ADD COLUMN alimtalk_sent_at DATETIME NULL COMMENT '알림톡 발송일시';

-- 인덱스 추가 (확정+미발송 조회 최적화)
CREATE INDEX idx_sync_orders_alimtalk ON sync_orders (site_code, management_status, alimtalk_sent);
