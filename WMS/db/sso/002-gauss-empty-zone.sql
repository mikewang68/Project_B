-- Compatible upgrade for existing SSO mapping tables, including Gauss A mode.
-- No mapping, account, company scope or existing zone claim is changed.
BEGIN;
ALTER TABLE wms_sso_subject_map ALTER COLUMN zone_code DROP NOT NULL;
ALTER TABLE wms_sso_subject_map ALTER COLUMN zone_code SET DEFAULT NULL;
COMMIT;
