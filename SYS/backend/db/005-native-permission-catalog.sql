-- Native SYS operation codes; grants remain an explicit deployment decision.
SET search_path TO iam,public;
BEGIN;
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,status)
SELECT v.code,v.parent,'BUTTON',v.code,v.code,'sys','active' FROM (VALUES
 ('sys:dict:type:edit','sys-dict'),
 ('sys:dict:type:delete','sys-dict'),
 ('sys:log:list:export','sys-log')
) v(code,parent) WHERE NOT EXISTS(SELECT 1 FROM iam_permission p WHERE p.permission_code=v.code);
COMMIT;
