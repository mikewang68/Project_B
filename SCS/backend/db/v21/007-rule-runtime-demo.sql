-- 007: 3 条演示规则的结构化参数/区域（R-S01 规则运行时）
-- 前置：001/002/004 已应用；只补充参数与区域，不改变规则启用/发布生命周期。
-- 说明：RULE-03 仍保持 DRAFT，演示时通过规则页“提交评审→发布”后再触发。

BEGIN;

-- RULE-01 未戴安全帽：置信度阈值 + 违规类型
INSERT INTO safety.safety_rule_version
    (rule_id, version_no, status_code, params, actions, version_note, author_name_snapshot, created_at)
SELECT r.id, 'v1.0', 'ACTIVE',
       '[{"label":"置信度阈值(%)","value":"80","danger":false,"hint":"AI 事件置信度低于该值不命中"},{"label":"违规类型","value":"未佩戴安全帽/未穿反光衣","danger":false,"hint":null}]'::jsonb,
       '["现场声光提醒","通知安全员"]'::jsonb,
       'R-S01 演示规则参数（运行时求值）', 'system', now()
FROM safety.safety_rule r
WHERE r.rule_code = 'SIM-DEMO-SCS-20261113-RULE-01'
  AND NOT EXISTS (SELECT 1 FROM safety.safety_rule_version v
                  WHERE v.rule_id = r.id AND v.version_no = 'v1.0');

-- RULE-02 设备防撞距离：告警距离阈值
INSERT INTO safety.safety_rule_version
    (rule_id, version_no, status_code, params, actions, version_note, author_name_snapshot, created_at)
SELECT r.id, 'v1.0', 'ACTIVE',
       '[{"label":"告警距离(m)","value":"5.4","danger":true,"hint":"相对距离低于该值生成防碰撞告警"},{"label":"风险等级","value":"SEVERE","danger":false,"hint":null}]'::jsonb,
       '["现场声光提醒","减速请求"]'::jsonb,
       'R-S01 演示规则参数（运行时求值）', 'system', now()
FROM safety.safety_rule r
WHERE r.rule_code = 'SIM-DEMO-SCS-20261113-RULE-02'
  AND NOT EXISTS (SELECT 1 FROM safety.safety_rule_version v
                  WHERE v.rule_id = r.id AND v.version_no = 'v1.0');

-- RULE-03 人员闯入危险区：触发区域（保持 DRAFT，演示时发布生效）
INSERT INTO safety.safety_rule_version
    (rule_id, version_no, status_code, params, actions, version_note, author_name_snapshot, created_at)
SELECT r.id, 'v1.0', 'ACTIVE',
       '[{"label":"触发区域","value":"装卸区 A","danger":true,"hint":"人员进入该区域即命中"}]'::jsonb,
       '["现场声光提醒","通知安全员"]'::jsonb,
       'R-S01 演示规则参数（运行时求值）', 'system', now()
FROM safety.safety_rule r
WHERE r.rule_code = 'SIM-DEMO-SCS-20261113-RULE-03'
  AND NOT EXISTS (SELECT 1 FROM safety.safety_rule_version v
                  WHERE v.rule_id = r.id AND v.version_no = 'v1.0');

INSERT INTO safety.safety_rule_area (rule_id, area_code)
SELECT r.id, '装卸区 A'
FROM safety.safety_rule r
WHERE r.rule_code = 'SIM-DEMO-SCS-20261113-RULE-03'
  AND NOT EXISTS (SELECT 1 FROM safety.safety_rule_area a
                  WHERE a.rule_id = r.id AND a.area_code = '装卸区 A');

-- 闯入规则演示用预警等级；不影响已发布规则的生命周期。
UPDATE safety.safety_rule
SET risk_level_code = 'WARNING', updated_at = now()
WHERE rule_code = 'SIM-DEMO-SCS-20261113-RULE-03'
  AND risk_level_code = 'NORMAL';

COMMIT;
