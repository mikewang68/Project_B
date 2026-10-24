-- SCS 压测种子：60,000 条告警，覆盖近 30 天窗口
-- 维度分布：4 风险等级 × 5 状态 × 4 区域 × 3 来源，occurred_at 均匀散布 30 天
-- （本文档为只读压测准备；dedup_key/alert_no 全局唯一满足唯一约束）
INSERT INTO safety.safety_alert (
    id, alert_no, title, source, event_type, risk_level_code, status_code,
    area_code, area_name_snapshot, target_object_type, target_object_id,
    target_object_name_snapshot, decision_source_type, origin, dedup_key,
    occurred_at, created_at, updated_at
)
SELECT
    ('{' || md5('lt-alert-' || g.i::text) || '}')::uuid,
    'ALM-LT-' || to_char(now() - (g.i % 2592000) * interval '1 second', 'yyyyMMdd') || '-' || lpad(g.i::text, 6, '0'),
    CASE g.i % 3 WHEN 0 THEN '人员进入龙门吊作业盲区'
                  WHEN 1 THEN '未佩戴安全帽'
                  ELSE '设备交汇距离低于安全阈值' END,
    (ARRAY['人员安全','设备防碰撞','AI违规'])[1 + g.i % 3],
    (ARRAY['人员越界','未佩戴安全帽','设备交汇风险','人员滞留'])[1 + g.i % 4],
    (ARRAY['NORMAL','WARNING','SEVERE','URGENT'])[1 + g.i % 4],
    (ARRAY['PENDING_CONFIRM','PROCESSING','PENDING_REVIEW','CLOSED'])[1 + g.i % 4],
    NULL,
    (ARRAY['装卸区 A','装卸区 B','箱区 A','龙门吊作业区'])[1 + g.i % 4],
    CASE g.i % 3 WHEN 0 THEN '人员' WHEN 1 THEN '设备' ELSE '人员' END,
    'LT-OBJ-' || lpad((g.i % 500)::text, 4, '0'),
    CASE g.i % 3 WHEN 0 THEN '作业人员 P-' || lpad((g.i % 500)::text, 4, '0')
                  WHEN 1 THEN '龙门吊 G-CRANE-' || lpad((g.i % 6)::text, 2, '0')
                  ELSE '外协人员 P-' || lpad((g.i % 500)::text, 4, '0') END,
    (ARRAY['FENCE','COLLISION','AI_MODEL'])[1 + g.i % 3],
    CASE g.i % 20 WHEN 0 THEN 'EDGE_REPLAY' ELSE 'CLOUD' END,
    'lt-dedup-' || g.i::text,
    now() - (g.i % 2592000) * interval '1 second',
    now() - (g.i % 2592000) * interval '1 second',
    now() - (g.i % 2592000) * interval '1 second'
FROM generate_series(1, 60000) AS g(i);

-- 统计口径 sanity：按窗口计数
SELECT '总告警' k, count(*)::text v FROM safety.safety_alert
UNION ALL SELECT '近7天', count(*)::text FROM safety.safety_alert WHERE occurred_at >= now() - interval '7 days'
UNION ALL SELECT '未关闭', count(*)::text FROM safety.safety_alert WHERE status_code <> 'CLOSED';
