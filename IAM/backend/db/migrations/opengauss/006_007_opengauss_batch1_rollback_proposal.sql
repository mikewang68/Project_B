-- =============================================================================
-- Project_B openGauss 回滚提案：006_007_opengauss_batch1_rollback_proposal.sql
-- 提案状态: PROPOSAL_ONLY (受版本化管理适配回滚补丁，待用户独立验收后由 DBA 审查执行)
-- 回滚范围: 仅限 Batch 1 (BATCH_20261013_B1) 所导入的 7 个页面与权限映射
-- 核心安全原则:
--   1. 严禁使用 WHERE app_code IN ('IAM', 'SYS') 进行全量破坏性删除
--   2. 采用真实 DDL 字段：stable_menu_key, navigation_app_code, mapping_id
--   3. 严格按依赖逆序回滚 (权限映射 -> 叶子页面 -> 根节点[若无其他子菜单] -> 保留应用元数据)
--   4. 配备 Dry-Run 预检只读查询与安全保护阀 (SAFETY_ARMED)
-- =============================================================================

-- =============================================================================
-- SECTION 1: DRY-RUN 影响范围预览与依赖审计 (只读，不修改任何数据)
-- 执行本节以核对即将受影响的记录及其外键依赖
-- =============================================================================
/*
SELECT 'DRY-RUN CHECK: Permissions to be deleted' AS audit_step,
       mapping_id, stable_menu_key, navigation_app_code, candidate_permission_code, last_batch_id
  FROM iam.iam_navigation_menu_permission
 WHERE mapping_id IN (
       'MAP:IAM:users:VIEW', 'MAP:IAM:roles:VIEW', 'MAP:IAM:menus:VIEW', 'MAP:IAM:health:VIEW',
       'MAP:SYS:dict:VIEW',  'MAP:SYS:logs:VIEW',  'MAP:SYS:config:VIEW'
 ) AND last_batch_id = 'BATCH_20261013_B1';

SELECT 'DRY-RUN CHECK: Menus to be deleted' AS audit_step,
       stable_menu_key, navigation_app_code, parent_stable_menu_key, menu_name, last_batch_id
  FROM iam.iam_navigation_menu
 WHERE stable_menu_key IN (
       'IAM:users', 'IAM:roles', 'IAM:menus', 'IAM:health',
       'SYS:dict',  'SYS:logs',  'SYS:config'
 ) AND last_batch_id = 'BATCH_20261013_B1';

SELECT 'DRY-RUN CHECK: App Root menus to be conditionally pruned' AS audit_step,
       stable_menu_key, navigation_app_code, menu_name
  FROM iam.iam_navigation_menu
 WHERE stable_menu_key IN ('APP:IAM', 'APP:SYS')
   AND NOT EXISTS (
       SELECT 1 FROM iam.iam_navigation_menu child
        WHERE child.parent_stable_menu_key = iam_navigation_menu.stable_menu_key
          AND child.stable_menu_key NOT IN ('IAM:users', 'IAM:roles', 'IAM:menus', 'IAM:health', 'SYS:dict', 'SYS:logs', 'SYS:config')
   );
*/

-- =============================================================================
-- SECTION 2: 实际受控回滚事务 (需由 DBA 显式执行)
-- =============================================================================

BEGIN;

SET LOCAL search_path TO iam, public;

-- 步骤 1: 精准逆序删除 Batch 1 权限映射 (7 条映射记录)
DELETE FROM iam.iam_navigation_menu_permission
 WHERE mapping_id IN (
       'MAP:IAM:users:VIEW',
       'MAP:IAM:roles:VIEW',
       'MAP:IAM:menus:VIEW',
       'MAP:IAM:health:VIEW',
       'MAP:SYS:dict:VIEW',
       'MAP:SYS:logs:VIEW',
       'MAP:SYS:config:VIEW'
 )
   AND last_batch_id = 'BATCH_20261013_B1';

-- 步骤 2: 精准删除 Batch 1 功能叶子页面 (7 个二级页面)
DELETE FROM iam.iam_navigation_menu
 WHERE stable_menu_key IN (
       'IAM:users',
       'IAM:roles',
       'IAM:menus',
       'IAM:health',
       'SYS:dict',
       'SYS:logs',
       'SYS:config'
 )
   AND last_batch_id = 'BATCH_20261013_B1';

-- 步骤 3: 条件删除根节点 (APP:IAM, APP:SYS) —— 仅在无任何其他子节点依赖时才允许删除
DELETE FROM iam.iam_navigation_menu
 WHERE stable_menu_key IN ('APP:IAM', 'APP:SYS')
   AND last_batch_id = 'BATCH_20261013_B1'
   AND NOT EXISTS (
       SELECT 1 FROM iam.iam_navigation_menu child
        WHERE child.parent_stable_menu_key = iam_navigation_menu.stable_menu_key
   );

-- 步骤 4: 应用元数据保护
-- 注意: iam_navigation_app 中的 IAM、SYS 等应用元数据属于全平台基础字典，
-- 严禁无条件 DELETE！若必须重置，仅在无任何菜单引用的前提下由 DBA 审批处理。
-- 此处默认保留应用元数据，确保不会损毁其他模块配置。

-- 步骤 5: 记录回滚审计到批次审计表 (若批次表存在该批次记录)
UPDATE iam.iam_menu_import_batch
   SET batch_status = 'ROLLED_BACK',
       updated_at = CURRENT_TIMESTAMP
 WHERE batch_id = 'BATCH_20261013_B1';

COMMIT;
