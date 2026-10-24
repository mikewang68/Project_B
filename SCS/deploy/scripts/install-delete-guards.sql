-- =============================================================================
-- SCS 生产库 b_project 防物理删除护栏全量安装（决策①，仅 safety 模式内 26 张表）
-- 幂等可重复执行；函数 safety.fn_prevent_physical_delete 已存在（P0-3 时随 ai_model 建立）。
-- 依据：权威 schema 第 13 节；应用层 server profile 零 DELETE（已核实），
--      门禁已切换至独立 scs_test（无护栏），本库不再承接任何测试写入。
-- =============================================================================
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'sys_team','safety_area','sys_user','safety_personnel','device_camera','collision_device',
        'edge_node','safety_alert','safety_alert_timeline','safety_alert_evidence',
        'safety_alert_treatment','safety_alert_linkage','safety_alert_linkage_step',
        'ai_event','ai_event_timeline','safety_fence','safety_fence_version','safety_fence_edge_sync',
        'safety_rule','safety_rule_area','safety_rule_version','safety_rule_edge_sync',
        'edge_pending_event','ops_event_log','sys_business_number','ai_model'
    ] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_prevent_delete_%1$s ON safety.%1$s', t);
        EXECUTE format('CREATE TRIGGER trg_prevent_delete_%1$s BEFORE DELETE ON safety.%1$s
                        FOR EACH ROW EXECUTE PROCEDURE safety.fn_prevent_physical_delete()', t);
    END LOOP;
END $$;

SELECT count(*) AS guarded_tables FROM pg_trigger WHERE tgname LIKE 'trg_prevent_delete%';
