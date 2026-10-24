-- SCS operation catalog only. No accounts and no automatic feature publication.
SET search_path TO iam,public;
BEGIN;
INSERT INTO iam_permission(id,node_type,permission_code,permission_name,system_code,status)
SELECT v.code,'BUTTON',v.code,v.code,'safety','active' FROM (VALUES
 ('safety:fence:export'),
 ('safety:fence:list:view'),
 ('safety:fence:publish'),
 ('safety:home:map:view'),
 ('safety:mobile:export'),
 ('safety:mobile:view'),
 ('safety:module:ai:assign'),
 ('safety:module:ai:edit'),
 ('safety:module:ai:export'),
 ('safety:module:ai:model:edit'),
 ('safety:module:ai:model:execute'),
 ('safety:module:ai:model:export'),
 ('safety:module:ai:model:view'),
 ('safety:module:ai:view'),
 ('safety:module:alert:approve'),
 ('safety:module:alert:export'),
 ('safety:module:alert:handle'),
 ('safety:module:alert:view'),
 ('safety:module:collision:execute'),
 ('safety:module:collision:export'),
 ('safety:module:collision:view'),
 ('safety:module:ops:execute'),
 ('safety:module:ops:export'),
 ('safety:module:ops:view'),
 ('safety:module:rule:approve'),
 ('safety:module:rule:edit'),
 ('safety:module:rule:execute'),
 ('safety:module:rule:export'),
 ('safety:module:rule:view'),
 ('safety:module:stat:export'),
 ('safety:module:stat:view'),
 ('safety:person:config:edit'),
 ('safety:person:export'),
 ('safety:person:map:view'),
 ('safety:screen:export'),
 ('safety:screen:view')
) v(code) WHERE NOT EXISTS(SELECT 1 FROM iam_permission p WHERE p.permission_code=v.code);
COMMIT;
