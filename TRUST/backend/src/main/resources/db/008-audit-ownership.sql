-- Existing page actions were accepted only in real IAM mode.
ALTER TABLE audit_page_action ADD COLUMN identity_mode VARCHAR(16) NOT NULL DEFAULT 'real';
ALTER TABLE audit_page_action ADD CONSTRAINT audit_page_identity_mode
  CHECK(identity_mode IN ('real','simulated','local'));
-- Delivery ownership must outlive scheduling gaps without allowing a stale worker to confirm.
ALTER TABLE audit_delivery ADD COLUMN lease_token VARCHAR(36);
ALTER TABLE audit_delivery ADD COLUMN lease_until TIMESTAMP;
