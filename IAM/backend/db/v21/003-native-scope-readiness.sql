-- Apply to the fingerprint-verified IAM database before publishing the readiness guard.
-- Existing NULL values do NOT authorize V2 L2/L3/L4 consumer access.
-- Prepare only independently verified original bindings using an explicit deployment manifest.
-- New identities are stamped by NativeOnboardingWorker after actual native provisioning.
BEGIN;
LOCK TABLE iam.sso_subject_binding IN ACCESS EXCLUSIVE MODE NOWAIT;
ALTER TABLE iam.sso_subject_binding ADD COLUMN IF NOT EXISTS native_scope_json TEXT;
COMMENT ON COLUMN iam.sso_subject_binding.native_scope_json IS
  'Native provisioned tuple limit, not an authorization grant; NULL blocks scoped V2 consumers';
COMMIT;
-- Compatible rollback keeps this nullable column and provisioned snapshots; never restore an old whole database.
