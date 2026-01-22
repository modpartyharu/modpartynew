-- App Version Table
CREATE TABLE app_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version VARCHAR(50) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO app_version (version, description) VALUES ('0.0.1', 'Initial version');

-- Store Info Table (멀티 스토어 지원)
CREATE TABLE store_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_code VARCHAR(100) NOT NULL UNIQUE COMMENT 'Imweb 사이트 코드 (고유 식별자)',
    site_name VARCHAR(255) COMMENT '사이트 이름',
    site_url VARCHAR(500) COMMENT '사이트 URL',
    unit_code VARCHAR(100) COMMENT '유닛 코드',
    admin_email VARCHAR(255) COMMENT '관리자 이메일',
    company_name VARCHAR(255) COMMENT '회사명',
    representative_name VARCHAR(100) COMMENT '대표자명',
    business_number VARCHAR(50) COMMENT '사업자등록번호',
    phone VARCHAR(50) COMMENT '연락처',
    address TEXT COMMENT '주소',
    is_active BOOLEAN DEFAULT TRUE COMMENT '활성화 상태',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_site_code (site_code),
    INDEX idx_is_active (is_active)
) COMMENT '멀티 스토어 정보 관리';

-- OAuth Token Table (스토어별 토큰 관리)
CREATE TABLE oauth_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_code VARCHAR(100) NOT NULL COMMENT 'Imweb 사이트 코드',
    access_token TEXT NOT NULL COMMENT '액세스 토큰',
    token_type VARCHAR(50) DEFAULT 'Bearer' COMMENT '토큰 타입',
    expires_in INT COMMENT '토큰 만료 시간(초)',
    expires_at TIMESTAMP COMMENT '토큰 만료 일시',
    refresh_token TEXT COMMENT '리프레시 토큰',
    refresh_token_expires_at TIMESTAMP COMMENT '리프레시 토큰 만료 일시',
    scopes TEXT COMMENT '허용된 스코프 목록',
    issued_at TIMESTAMP COMMENT '토큰 발급 일시',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_site_code (site_code),
    CONSTRAINT fk_oauth_token_store FOREIGN KEY (site_code) REFERENCES store_info(site_code) ON DELETE CASCADE
) COMMENT '스토어별 OAuth 토큰 관리';
