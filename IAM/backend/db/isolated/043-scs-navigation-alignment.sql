-- 043-scs-navigation-alignment.sql
-- SCS 导航投影与应用内左侧菜单对齐（雷文授权 SCS 侧自行处理）
--
-- 背景：SCS 应用内左侧菜单（frontend/src/components/layout/AppSidebar.vue 与
-- docs/scs_module_menu.csv）中，「安全态势」是独立顶级项，不属于「作业安全」分组；
-- 而 IAM 导航投影里它被挂在 SCS:dir-work 下。结果是低权限账号在统一门户里
-- 会看到「作业安全」分组下只有一个「安全态势」，与应用内结构不一致。
-- 另：应用显示名是「安全安防系统」，与实际系统名「安全卡控系统」不符。
--
-- 本迁移只改 SCS 自己的导航行，不触碰其他系统；幂等，可重复执行。
-- 注意：这些行由雷文侧投影批次（SCS_SCOPED_ROUTES_V1 / UNIFIED_LIVE_V1）生成，
-- 若日后重新投影，需确认本修正仍保留；content_sha256 运行时未参与校验，此处不重算。

BEGIN;

-- 1. 应用显示名：安全安防系统 → 安全卡控系统
UPDATE iam.iam_navigation_app
   SET display_name = '安全卡控系统',
       updated_at   = now()
 WHERE app_code = 'SCS'
   AND display_name = '安全安防系统';

UPDATE iam.iam_navigation_menu
   SET menu_name              = '安全卡控系统',
       projected_display_title = '安全卡控系统',
       row_version            = row_version + 1,
       updated_by             = 'SCS_NAV_ALIGNMENT',
       last_operation_source  = 'SYSTEM_MIGRATION',
       last_batch_id          = 'SCS_NAV_ALIGNMENT_V1',
       updated_at             = now()
 WHERE navigation_app_code = 'SCS'
   AND stable_menu_key = 'APP:SCS'
   AND menu_name = '安全安防系统';

-- 2. 「安全态势」提升为顶级项，排在四个分组之前
--    hierarchy_code 本就是 SCS.01（顶级形态），只有 parent 挂错了
UPDATE iam.iam_navigation_menu
   SET parent_stable_menu_key = 'APP:SCS',
       normalized_level       = 2,
       sibling_sort_order     = 0,
       row_version            = row_version + 1,
       updated_by             = 'SCS_NAV_ALIGNMENT',
       last_operation_source  = 'SYSTEM_MIGRATION',
       last_batch_id          = 'SCS_NAV_ALIGNMENT_V1',
       updated_at             = now()
 WHERE navigation_app_code = 'SCS'
   AND stable_menu_key = 'SCS:overview'
   AND parent_stable_menu_key = 'SCS:dir-work';

COMMIT;

-- 核验（应看到 SCS:overview 的 parent 为 APP:SCS、sort 为 0）：
-- SELECT stable_menu_key, parent_stable_menu_key, normalized_level, sibling_sort_order, menu_name
--   FROM iam.iam_navigation_menu WHERE navigation_app_code = 'SCS'
--  ORDER BY normalized_level, sibling_sort_order;
