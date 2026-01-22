-- 관리상태 변경 이력 테이블
CREATE TABLE IF NOT EXISTS sync_order_status_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sync_order_id BIGINT NOT NULL COMMENT '주문 ID (sync_orders.id)',
    site_code VARCHAR(50) NOT NULL COMMENT '사이트 코드',
    previous_status VARCHAR(50) COMMENT '이전 상태',
    new_status VARCHAR(50) NOT NULL COMMENT '변경 후 상태',
    carryover_round INT COMMENT '이월 회차 (1~5)',
    changed_by VARCHAR(100) DEFAULT 'admin' COMMENT '변경자',
    changed_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '변경일시 (KST)',

    INDEX idx_sync_order_id (sync_order_id),
    INDEX idx_site_code (site_code),
    INDEX idx_changed_at (changed_at),

    FOREIGN KEY (sync_order_id) REFERENCES sync_orders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='관리상태 변경 이력';

-- sync_orders에 이월 회차 컬럼 추가
ALTER TABLE sync_orders ADD COLUMN carryover_round INT COMMENT '이월 회차 (1~5)' AFTER management_status;
