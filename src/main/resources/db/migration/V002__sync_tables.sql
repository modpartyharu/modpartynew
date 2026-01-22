-- Sync 기능을 위한 테이블 생성
-- 주문 정보를 기반으로 상품 정보, 회원 프로필 정보를 포함한 통합 테이블

-- 카테고리 테이블 (1:N 매핑 지원)
CREATE TABLE sync_categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_code VARCHAR(100) NOT NULL COMMENT 'Imweb 사이트 코드',
    category_code VARCHAR(100) NOT NULL COMMENT '카테고리 코드',
    name VARCHAR(255) COMMENT '카테고리명',
    parent_code VARCHAR(100) COMMENT '부모 카테고리 코드',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_site_category (site_code, category_code),
    INDEX idx_site_code (site_code),
    INDEX idx_parent_code (parent_code)
) COMMENT '동기화된 카테고리 정보';

-- 주문 통합 테이블 (주문 + 상품 + 회원 정보 결합)
CREATE TABLE sync_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_code VARCHAR(100) NOT NULL COMMENT 'Imweb 사이트 코드',
    unit_code VARCHAR(100) COMMENT '유닛 코드',

    -- 주문 기본 정보
    order_no BIGINT NOT NULL COMMENT '주문번호',
    order_status VARCHAR(50) COMMENT '주문상태',
    order_type VARCHAR(50) COMMENT '주문유형',
    sale_channel VARCHAR(50) COMMENT '판매채널',
    device VARCHAR(50) COMMENT '주문기기',
    country VARCHAR(50) COMMENT '주문국가',
    currency VARCHAR(10) COMMENT '통화',

    -- 금액 정보
    total_price BIGINT DEFAULT 0 COMMENT '총 가격',
    total_payment_price BIGINT DEFAULT 0 COMMENT '총 결제금액',
    total_delivery_price BIGINT DEFAULT 0 COMMENT '총 배송비',
    total_discount_price BIGINT DEFAULT 0 COMMENT '총 할인금액',

    -- 주문자 기본 정보 (주문 API에서)
    orderer_name VARCHAR(100) COMMENT '주문자명',
    orderer_email VARCHAR(255) COMMENT '주문자 이메일',
    orderer_call VARCHAR(50) COMMENT '주문자 연락처',
    is_member VARCHAR(1) COMMENT '회원여부 (Y/N)',
    member_code VARCHAR(100) COMMENT '회원코드',
    member_uid VARCHAR(255) COMMENT '회원 UID',

    -- 회원 상세 정보 (Member API에서)
    member_gender VARCHAR(1) COMMENT '회원 성별 (M/F)',
    member_birth VARCHAR(20) COMMENT '회원 생년월일',
    member_join_time VARCHAR(50) COMMENT '회원 가입일시',
    member_point INT COMMENT '회원 포인트',
    member_grade VARCHAR(50) COMMENT '회원 등급',
    member_social_login VARCHAR(100) COMMENT '소셜로그인 타입',
    member_sms_agree VARCHAR(1) COMMENT 'SMS 수신동의',
    member_email_agree VARCHAR(1) COMMENT '이메일 수신동의',

    -- 결제 정보 (첫번째 결제 기준)
    payment_no VARCHAR(50) COMMENT '결제번호',
    payment_status VARCHAR(50) COMMENT '결제상태',
    payment_method VARCHAR(50) COMMENT '결제수단',
    pg_name VARCHAR(100) COMMENT 'PG사 이름',
    paid_price BIGINT DEFAULT 0 COMMENT '결제금액',
    payment_complete_time VARCHAR(50) COMMENT '결제완료일시',

    -- 배송 정보 (첫번째 섹션 기준)
    receiver_name VARCHAR(100) COMMENT '수령인명',
    receiver_call VARCHAR(50) COMMENT '수령인 연락처',
    delivery_zipcode VARCHAR(20) COMMENT '배송지 우편번호',
    delivery_addr1 VARCHAR(500) COMMENT '배송지 주소1',
    delivery_addr2 VARCHAR(500) COMMENT '배송지 주소2',
    delivery_city VARCHAR(100) COMMENT '배송지 도시',
    delivery_state VARCHAR(100) COMMENT '배송지 주/도',
    delivery_country VARCHAR(100) COMMENT '배송지 국가',
    delivery_memo TEXT COMMENT '배송메모',

    -- 주문 섹션 상태
    order_section_status VARCHAR(50) COMMENT '주문섹션 상태',
    delivery_type VARCHAR(50) COMMENT '배송유형',

    -- 상품 정보 (주문 항목 기준, 첫번째 상품 또는 대표 상품)
    prod_no INT COMMENT '상품번호',
    prod_name VARCHAR(500) COMMENT '상품명',
    prod_code VARCHAR(100) COMMENT '상품코드',
    prod_status VARCHAR(50) COMMENT '상품상태',
    prod_type VARCHAR(50) COMMENT '상품유형',
    item_price BIGINT DEFAULT 0 COMMENT '상품가격',
    item_qty INT DEFAULT 1 COMMENT '상품수량',
    option_info JSON COMMENT '상품옵션 정보 (JSON)',

    -- 상품 상세 정보 (Product API에서)
    prod_brand VARCHAR(200) COMMENT '상품 브랜드',
    prod_event_words VARCHAR(500) COMMENT '이벤트 문구',
    prod_review_count INT DEFAULT 0 COMMENT '상품 리뷰수',
    prod_is_badge_best VARCHAR(1) COMMENT 'BEST 배지',
    prod_is_badge_hot VARCHAR(1) COMMENT 'HOT 배지',
    prod_is_badge_new VARCHAR(1) COMMENT 'NEW 배지',
    prod_simple_content TEXT COMMENT '상품 간단설명',
    prod_image_url VARCHAR(1000) COMMENT '상품 대표이미지 URL',

    -- 폼 데이터 (주문 시 입력한 추가 정보)
    form_data JSON COMMENT '주문 폼 데이터 (JSON)',

    -- 전체 상품 목록 (여러 상품 주문 시)
    all_products JSON COMMENT '주문 전체 상품 목록 (JSON)',

    -- 주문 일시
    order_time VARCHAR(50) COMMENT '주문일시 (wtime)',
    admin_url VARCHAR(500) COMMENT '관리자 URL',

    -- 동기화 정보
    synced_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '최초 동기화 일시',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 업데이트 일시',

    UNIQUE KEY uk_site_order (site_code, order_no),
    INDEX idx_site_code (site_code),
    INDEX idx_order_time (order_time),
    INDEX idx_order_status (order_status),
    INDEX idx_payment_status (payment_status),
    INDEX idx_member_code (member_code),
    INDEX idx_prod_no (prod_no),
    INDEX idx_synced_at (synced_at)
) COMMENT '동기화된 주문 통합 정보 (주문+상품+회원)';

-- 주문-카테고리 매핑 테이블 (1:N 관계)
CREATE TABLE sync_order_categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sync_order_id BIGINT NOT NULL COMMENT 'sync_orders.id',
    category_code VARCHAR(100) NOT NULL COMMENT '카테고리 코드',
    site_code VARCHAR(100) NOT NULL COMMENT 'Imweb 사이트 코드',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sync_order_id (sync_order_id),
    INDEX idx_category_code (category_code),
    INDEX idx_site_code (site_code),
    CONSTRAINT fk_sync_order_category_order FOREIGN KEY (sync_order_id)
        REFERENCES sync_orders(id) ON DELETE CASCADE
) COMMENT '주문-카테고리 매핑 테이블';

-- 동기화 상태 테이블 (동기화 이력 관리)
CREATE TABLE sync_status (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_code VARCHAR(100) NOT NULL COMMENT 'Imweb 사이트 코드',
    sync_type VARCHAR(50) NOT NULL COMMENT '동기화 유형 (ORDERS, CATEGORIES)',
    status VARCHAR(50) NOT NULL COMMENT '상태 (RUNNING, COMPLETED, FAILED)',
    total_count INT DEFAULT 0 COMMENT '전체 건수',
    synced_count INT DEFAULT 0 COMMENT '동기화된 건수',
    failed_count INT DEFAULT 0 COMMENT '실패 건수',
    start_date VARCHAR(20) COMMENT '동기화 시작일 (YYYY-MM-DD)',
    end_date VARCHAR(20) COMMENT '동기화 종료일 (YYYY-MM-DD)',
    error_message TEXT COMMENT '에러 메시지',
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '시작 시간',
    completed_at TIMESTAMP NULL COMMENT '완료 시간',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_site_code (site_code),
    INDEX idx_sync_type (sync_type),
    INDEX idx_status (status),
    INDEX idx_started_at (started_at)
) COMMENT '동기화 상태 및 이력';
