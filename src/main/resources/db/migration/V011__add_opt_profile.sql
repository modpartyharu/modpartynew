-- V011: 프로필 URL 필드 추가

-- 프로필 URL 컬럼 추가 (http/https 링크)
ALTER TABLE sync_orders
    ADD COLUMN opt_profile VARCHAR(500) NULL COMMENT '프로필 URL (http/https 링크)';
