-- 스케줄러 기반 자동 동기화 기능 지원을 위한 스키마 변경

-- 1. sync_status 테이블에 동기화 모드 컬럼 추가 (수동/자동)
ALTER TABLE sync_status
    ADD COLUMN sync_mode VARCHAR(20) DEFAULT 'MANUAL' COMMENT '동기화 모드 (MANUAL: 수동, AUTO: 자동)' AFTER sync_type;

-- sync_mode 인덱스 추가
ALTER TABLE sync_status ADD INDEX idx_sync_mode (sync_mode);

-- 2. 배치용 OAuth 토큰 테이블 (어드민 토큰과 분리 관리)
-- 스케줄러가 독립적으로 토큰을 관리하여 어드민 사용자와 충돌 방지
CREATE TABLE oauth_token_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_code VARCHAR(50) NOT NULL COMMENT 'Imweb 사이트 코드',
    access_token TEXT NOT NULL COMMENT '액세스 토큰',
    token_type VARCHAR(20) DEFAULT 'Bearer' COMMENT '토큰 타입',
    expires_in INT COMMENT '만료 시간(초)',
    expires_at DATETIME COMMENT '만료 일시',
    refresh_token TEXT COMMENT '리프레시 토큰',
    refresh_token_expires_at DATETIME COMMENT '리프레시 토큰 만료 일시',
    scopes VARCHAR(500) COMMENT '스코프 목록',
    issued_at DATETIME COMMENT '발급 일시',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_site_code (site_code)
) COMMENT '배치/스케줄러용 OAuth 토큰 (어드민 토큰과 분리)';

-- 3. 스케줄러 상태 관리 테이블
-- 스케줄러 On/Off 상태 및 마지막 실행 시간 관리
CREATE TABLE scheduler_status (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_code VARCHAR(50) NOT NULL COMMENT 'Imweb 사이트 코드',
    scheduler_type VARCHAR(50) NOT NULL COMMENT '스케줄러 유형 (ORDER_SYNC 등)',
    is_enabled BOOLEAN DEFAULT FALSE COMMENT '스케줄러 활성화 여부 (기본 OFF)',
    last_run_at DATETIME COMMENT '마지막 실행 시간',
    last_success_at DATETIME COMMENT '마지막 성공 시간',
    last_error_message TEXT COMMENT '마지막 에러 메시지',
    next_run_at DATETIME COMMENT '다음 예정 실행 시간',
    run_interval_minutes INT DEFAULT 1 COMMENT '실행 간격(분)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_site_scheduler (site_code, scheduler_type),
    INDEX idx_is_enabled (is_enabled),
    INDEX idx_scheduler_type (scheduler_type)
) COMMENT '스케줄러 상태 관리';
