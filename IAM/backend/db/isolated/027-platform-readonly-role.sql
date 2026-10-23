-- Defines a global read-only role, without assigning it to any user or granting export.
-- Explicit application bindings and project/data scopes are still required.
BEGIN;
INSERT INTO iam.iam_permission(id,node_type,permission_code,permission_name,system_code,status)
SELECT code,'BUTTON',code,name,'trust','active'
FROM (VALUES
 ('trust:event:read','可信业务事件-读取'),('trust:evidence:read','证据-读取'),
 ('trust:task:read','后台任务-读取'),('trust:audit:read','审计-读取'),
 ('trust:operations:read','运行状态-读取'),('trust:identity:read','身份-读取'),
 ('trust:wallet:read','钱包-读取')
) AS v(code,name)
WHERE NOT EXISTS(SELECT 1 FROM iam.iam_permission p WHERE p.permission_code=v.code);

INSERT INTO iam.iam_permission(id,node_type,permission_code,permission_name,system_code,status)
SELECT code,'BUTTON',code,name,'safety','active'
FROM (VALUES
 ('safety:home:map:view','安全态势-查看'),('safety:person:map:view','人员定位-查看'),
 ('safety:fence:list:view','电子围栏-查看'),('safety:module:collision:view','设备防碰撞-查看'),
 ('safety:module:ai:view','AI 违规事件-查看'),('safety:module:ai:model:view','AI 模型-查看'),
 ('safety:module:alert:view','告警中心-查看'),('safety:event:list:view','安全事件-查看'),
 ('safety:module:stat:view','统计分析-查看'),('safety:module:ops:view','运维状态-查看'),
 ('safety:module:rule:view','安全规则-查看')
) AS v(code,name)
WHERE NOT EXISTS(SELECT 1 FROM iam.iam_permission p WHERE p.permission_code=v.code);

INSERT INTO iam.iam_role(id,role_code,role_name,description,status)
SELECT 'role-platform-auditor-readonly','platform_auditor_readonly','全局只读',
 'Explicit cross-application read grants; no business writes or implicit export','active'
WHERE NOT EXISTS(SELECT 1 FROM iam.iam_role WHERE lower(role_code)='platform_auditor_readonly' AND deleted=0);

INSERT INTO iam.iam_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM iam.iam_role r CROSS JOIN iam.iam_permission p
WHERE lower(r.role_code)='platform_auditor_readonly' AND r.deleted=0 AND r.status='active'
 AND p.node_type='BUTTON' AND p.status='active'
 AND (p.permission_code LIKE '%:view' OR p.permission_code LIKE '%:read')
 AND NOT EXISTS(SELECT 1 FROM iam.iam_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
COMMIT;
