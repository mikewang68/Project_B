-- SCS 缺失操作权限补齐（2026-10-26）。
--
-- 背景：034 的 SCS 目录漏了 3 个后端强制要求的权限码，导致 IAM 无法授予，
-- 非 ADMIN 账号在对应功能上恒 403：
--   safety:fence:edit          POST/PUT /api/v1/fences、/{id}/submit-review、/{id}/disable、/{id}/simulate-mismatch
--   safety:mobile:execute      POST /api/v1/mobile/incidents/{id}/accept、/{id}/arrive
--   safety:module:stat:execute POST /api/v1/analytics/simulate-surge
--
-- 以及 1 个此前映射为 null 的写权限（SCS 已改为显式映射）：
--   safety:home:map:edit       POST /api/v1/overview/simulate-risk
--
-- 另补 2 个菜单分组权限码（SCS 左侧菜单 scs_module_menu.csv 使用，此前未登记）：
--   safety:work:view           作业安全（分组目录）
--   safety:event:view          事件管理（分组目录）
--
-- 不含任何账号、角色授权或菜单发布；授予动作由雷文按角色单独执行。
SET search_path TO iam,public;
BEGIN;
INSERT INTO iam_permission(id,node_type,permission_code,permission_name,system_code,status)
SELECT v.code,'BUTTON',v.code,v.code,'safety','active' FROM (VALUES
 ('safety:fence:edit'),
 ('safety:mobile:execute'),
 ('safety:module:stat:execute'),
 ('safety:home:map:edit'),
 ('safety:work:view'),
 ('safety:event:view')
) v(code) WHERE NOT EXISTS(SELECT 1 FROM iam_permission p WHERE p.permission_code=v.code);
COMMIT;
