-- V008: 알림톡 설정 테이블 추가
-- 테스트 발송 전화번호 등 알림톡 관련 설정 저장

-- 싱크 설정 테이블 (공통 설정)
CREATE TABLE IF NOT EXISTS sync_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_code VARCHAR(50) NOT NULL,
    config_key VARCHAR(100) NOT NULL,
    config_value VARCHAR(500),
    description VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_site_config (site_code, config_key)
);

-- 알림톡 발송 이력 테이블
CREATE TABLE IF NOT EXISTS alimtalk_send_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_code VARCHAR(50) NOT NULL,
    sync_order_id BIGINT,
    order_no BIGINT,
    template_id INT NOT NULL,
    template_name VARCHAR(100),
    receiver_phone VARCHAR(20) NOT NULL,
    receiver_name VARCHAR(100),
    message_content TEXT,
    send_type VARCHAR(20) NOT NULL DEFAULT 'STATUS_CHANGE', -- STATUS_CHANGE, MANUAL_TEST
    trigger_status VARCHAR(50),  -- 발송을 트리거한 상태 (확정, 환불(대기자환불) 등)
    result_code INT,
    result_message TEXT,
    is_success BOOLEAN DEFAULT FALSE,
    sent_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_site_code (site_code),
    INDEX idx_order_no (order_no),
    INDEX idx_sent_at (sent_at),
    INDEX idx_sync_order_id (sync_order_id),
    FOREIGN KEY (sync_order_id) REFERENCES sync_orders(id) ON DELETE SET NULL
);

-- 기본 설정값 삽입 (모드파티 사이트용)
-- INSERT INTO sync_config (site_code, config_key, config_value, description)
-- VALUES ('S20210109d4021b623ca2a', 'alimtalk_test_phone', '', '알림톡 테스트 발송 전화번호');
