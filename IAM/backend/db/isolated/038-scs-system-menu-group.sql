-- SCS menu group permission only; no account grants or navigation publication.
-- The legacy safety prefix belongs to the canonical SCS application, including
-- safety:sys:view. It does not grant access to the separate SYS application.
SET search_path TO iam,public;
BEGIN;
INSERT INTO iam_permission(id,node_type,permission_code,permission_name,system_code,status)
SELECT 'safety:sys:view','BUTTON','safety:sys:view','SCS system management group','safety','active'
WHERE NOT EXISTS(SELECT 1 FROM iam_permission WHERE permission_code='safety:sys:view');
COMMIT;
