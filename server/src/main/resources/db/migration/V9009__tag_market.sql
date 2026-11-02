-- ============================================================================
-- V9009 标签市场
-- 租户管理员上架本租户可用标签；其他租户提交使用申请，平台管理员审批后
-- 将申请租户追加到 tag_meta.tenant_id（由 TagMetaService.grantTenant 生成版本快照）。
-- 兼容：MySQL 8 / OceanBase MySQL 模式 / H2(MySQL 模式)
-- ============================================================================

CREATE TABLE tag_market_listing (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  tag_code        VARCHAR(64)  NOT NULL,
  owner_tenant_id VARCHAR(64)  NOT NULL,
  description     VARCHAR(512),
  status          VARCHAR(16)  NOT NULL,
  listed_by       VARCHAR(128) NOT NULL,
  created_at      DATETIME     NOT NULL,
  updated_at      DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (tag_code)
);
CREATE INDEX idx_tag_market_listing_owner ON tag_market_listing (owner_tenant_id, status);

CREATE TABLE tag_market_request (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  listing_id          BIGINT       NOT NULL,
  tag_code            VARCHAR(64)  NOT NULL,
  applicant_tenant_id VARCHAR(64)  NOT NULL,
  applicant_user      VARCHAR(128) NOT NULL,
  remark              VARCHAR(512),
  status              VARCHAR(16)  NOT NULL,
  reviewed_by         VARCHAR(128),
  review_remark       VARCHAR(512),
  created_at          DATETIME     NOT NULL,
  reviewed_at         DATETIME,
  PRIMARY KEY (id)
);
CREATE INDEX idx_tag_market_request_status ON tag_market_request (status, created_at);
CREATE INDEX idx_tag_market_request_tenant ON tag_market_request (applicant_tenant_id, status);
CREATE INDEX idx_tag_market_request_listing ON tag_market_request (listing_id, applicant_tenant_id);
