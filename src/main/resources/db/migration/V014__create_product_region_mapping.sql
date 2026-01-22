CREATE TABLE IF NOT EXISTS product_region_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT,
    site_code VARCHAR(64) NOT NULL,
    prod_no INT NOT NULL,
    region_name VARCHAR(100) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_site_prod (site_code, prod_no)
);

CREATE INDEX idx_prm_site_code ON product_region_mapping(site_code);
CREATE INDEX idx_prm_prod_no ON product_region_mapping(prod_no);
