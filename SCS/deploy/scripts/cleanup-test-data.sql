-- =============================================================================
-- SCS D-3: 清理 b_project 中集成测试遗留数据（OpenGaussStep* 系列前缀行）
-- 安全边界：
--   1. 只删除带 scs_step 测试前缀的行（幂等键/编号/节点列 LIKE 匹配），不触碰业务数据
--   2. 按外键依赖子表先删；ai_model 挂有防删触发器，仅能软删除
--   3. 事务执行，结尾输出各表清理行数
-- 用法：isula exec bpoc-opengauss su - omm -c "gsql -d b_project -f /tmp/cleanup-test-data.sql"
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;

DELETE FROM safety.safety_alert_timeline WHERE alert_id IN
    (SELECT id FROM safety.safety_alert WHERE alert_no LIKE 'scs_step%');
DELETE FROM safety.safety_alert_evidence WHERE alert_id IN
    (SELECT id FROM safety.safety_alert WHERE alert_no LIKE 'scs_step%');
DELETE FROM safety.safety_alert_treatment WHERE alert_id IN
    (SELECT id FROM safety.safety_alert WHERE alert_no LIKE 'scs_step%');
DELETE FROM safety.safety_alert_linkage_step WHERE linkage_id IN
    (SELECT l.id FROM safety.safety_alert_linkage l
        JOIN safety.safety_alert a ON a.id = l.alert_id WHERE a.alert_no LIKE 'scs_step%');
DELETE FROM safety.safety_alert_linkage WHERE alert_id IN
    (SELECT id FROM safety.safety_alert WHERE alert_no LIKE 'scs_step%');
DELETE FROM safety.safety_alert WHERE alert_no LIKE 'scs_step%';

DELETE FROM safety.ai_event_timeline WHERE ai_event_id IN
    (SELECT id FROM safety.ai_event WHERE event_no LIKE 'scs_step%');
DELETE FROM safety.ai_event WHERE event_no LIKE 'scs_step%';

DELETE FROM safety.edge_pending_event WHERE idempotency_key LIKE '%scs_step%';
DELETE FROM safety.ops_event_log WHERE edge_node_code LIKE '%scs_step%';

-- ai_model 有防删触发器：测试行只做软删除（预置 5 款正式配置不受影响）
UPDATE safety.ai_model SET is_deleted = true, updated_at = now()
    WHERE model_code LIKE '%scs_step%';

-- 残留报告（清理后应为 0；ai_model 仅统计未软删的测试行）
SELECT 'alert' AS kind, count(*) FROM safety.safety_alert WHERE alert_no LIKE 'scs_step%'
UNION ALL SELECT 'ai_event', count(*) FROM safety.ai_event WHERE event_no LIKE 'scs_step%'
UNION ALL SELECT 'edge_pending', count(*) FROM safety.edge_pending_event WHERE idempotency_key LIKE '%scs_step%'
UNION ALL SELECT 'ops_log', count(*) FROM safety.ops_event_log WHERE edge_node_code LIKE '%scs_step%'
UNION ALL SELECT 'ai_model_active_test', count(*) FROM safety.ai_model WHERE model_code LIKE '%scs_step%' AND is_deleted = false;

COMMIT;
