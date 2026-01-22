-- Create region_mappings table (region_name -> product_code mapping)
-- This migration must match the schema already applied in production DB to avoid Flyway checksum mismatch.

CREATE TABLE `region_mappings` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `region_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '지역명 (예: 압구정, 역삼)',
  `product_code` int NOT NULL COMMENT '상품 코드',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '생성일',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_region_product` (`region_name`,`product_code`),
  KEY `idx_region_name` (`region_name`),
  KEY `idx_product_code` (`product_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='지역별 상품코드 매핑';

