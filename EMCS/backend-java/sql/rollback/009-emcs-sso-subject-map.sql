-- Disable EMCS_SSO_ENABLED and stop the isolated instance first. Back up these tables before removal.
-- Removes only SSO binding/session data; existing users and energy data are untouched.
BEGIN;
DROP TABLE emcs_sso_session;
DROP TABLE emcs_sso_iam_session;
DROP TABLE emcs_sso_subject_map;
COMMIT;
