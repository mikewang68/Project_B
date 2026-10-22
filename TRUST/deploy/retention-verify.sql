-- Post-migration read-only structural checks. Run with the application role in trust_data.
-- All counts must agree; versions >= anchors. Foreign keys protect individual head references.
SELECT 'events' AS domain,(SELECT count(*) FROM events) AS anchors,(SELECT count(*) FROM events_head) AS heads,(SELECT count(*) FROM events_read) AS readable,(SELECT count(*) FROM events_versions) AS versions;
SELECT 'evidence' AS domain,(SELECT count(*) FROM evidence) AS anchors,(SELECT count(*) FROM evidence_head) AS heads,(SELECT count(*) FROM evidence_read) AS readable,(SELECT count(*) FROM evidence_versions) AS versions;
SELECT 'generic_event_data' AS domain,(SELECT count(*) FROM generic_event_data) AS anchors,(SELECT count(*) FROM generic_event_data_head) AS heads,(SELECT count(*) FROM generic_event_data_read) AS readable,(SELECT count(*) FROM generic_event_data_versions) AS versions;
SELECT 'wallets' AS domain,(SELECT count(*) FROM wallets) AS anchors,(SELECT count(*) FROM wallets_head) AS heads,(SELECT count(*) FROM wallets_read) AS readable,(SELECT count(*) FROM wallets_versions) AS versions;
SELECT 'wallet_bindings' AS domain,(SELECT count(*) FROM wallet_bindings) AS anchors,(SELECT count(*) FROM wallet_bindings_head) AS heads,(SELECT count(*) FROM wallet_bindings_read) AS readable,(SELECT count(*) FROM wallet_bindings_versions) AS versions;
SELECT 'wallet_requests' AS domain,(SELECT count(*) FROM wallet_requests) AS anchors,(SELECT count(*) FROM wallet_requests_head) AS heads,(SELECT count(*) FROM wallet_requests_read) AS readable,(SELECT count(*) FROM wallet_requests_versions) AS versions;
SELECT 'fabric_identity' AS domain,(SELECT count(*) FROM fabric_identity) AS anchors,(SELECT count(*) FROM fabric_identity_head) AS heads,(SELECT count(*) FROM fabric_identity_read) AS readable,(SELECT count(*) FROM fabric_identity_versions) AS versions;
SELECT 'fabric_certificate' AS domain,(SELECT count(*) FROM fabric_certificate) AS anchors,(SELECT count(*) FROM fabric_certificate_head) AS heads,(SELECT count(*) FROM fabric_certificate_read) AS readable,(SELECT count(*) FROM fabric_certificate_versions) AS versions;
SELECT 'audit_log_settings' AS domain,(SELECT count(*) FROM audit_log_settings) AS anchors,(SELECT count(*) FROM audit_log_settings_head) AS heads,(SELECT count(*) FROM audit_log_settings_read) AS readable,(SELECT count(*) FROM audit_log_settings_versions) AS versions;
SELECT 'audit_evidence' AS domain,(SELECT count(*) FROM audit_evidence) AS anchors,(SELECT count(*) FROM audit_evidence_head) AS heads,(SELECT count(*) FROM audit_evidence_read) AS readable,(SELECT count(*) FROM audit_evidence_versions) AS versions;
SELECT 'audit_delivery' AS domain,(SELECT count(*) FROM audit_delivery) AS anchors,(SELECT count(*) FROM audit_delivery_head) AS heads,(SELECT count(*) FROM audit_delivery_read) AS readable,(SELECT count(*) FROM audit_delivery_versions) AS versions;
SELECT 'audit_page_action' AS domain,(SELECT count(*) FROM audit_page_action) AS anchors,(SELECT count(*) FROM audit_page_action_head) AS heads,(SELECT count(*) FROM audit_page_action_read) AS readable,(SELECT count(*) FROM audit_page_action_versions) AS versions;
SELECT 'signing_attempts' AS domain,(SELECT count(*) FROM signing_attempts) AS anchors,(SELECT count(*) FROM signing_attempts_head) AS heads,(SELECT count(*) FROM signing_attempts_read) AS readable,(SELECT count(*) FROM signing_attempts_versions) AS versions;
SELECT 'tasks' AS domain,(SELECT count(*) FROM tasks) AS anchors,(SELECT count(*) FROM tasks_head) AS heads,(SELECT count(*) FROM tasks_read) AS readable,(SELECT count(*) FROM tasks_versions) AS versions;
-- Must return zero rows:
SELECT e.id FROM events e LEFT JOIN event_heads h ON h.root_id=e.root_id WHERE h.root_id IS NULL;
SELECT h.root_id FROM event_heads h JOIN events e ON e.id=h.current_id WHERE h.org_id<>e.org_id OR h.version<>e.version OR h.root_id<>e.root_id;
-- Must return false. Inspect role inheritance separately with the migration operator.
SELECT rolsuper OR rolcreatedb OR rolcreaterole AS excessive_role FROM pg_roles WHERE rolname=current_user;
SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='trust_data' AND c.relkind='r' AND (has_table_privilege(current_user,c.oid,'DELETE') OR has_table_privilege(current_user,c.oid,'TRUNCATE'));
-- Expected results are necessary but not sufficient: run actual denied UPDATE/DELETE tests too.
