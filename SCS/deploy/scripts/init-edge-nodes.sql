-- =============================================================================
-- SCS Phase 1 / Step 0: 边缘计算节点台账初始化数据
-- 物理表: safety.edge_node
-- 满足幂等性：当 node_code 存在时跳过，严禁重复插入或破坏生产唯一索引
-- =============================================================================

INSERT INTO safety.edge_node (
    node_code, node_name, area_code, ip_address, node_status_code, cloud_connected,
    autonomy_active, agent_version, active_rule_version, expected_rule_version,
    active_fence_version, expected_fence_version, clock_offset_ms, latency_ms,
    cpu_usage_pct, memory_usage_pct, disk_usage_pct, temperature_c, queue_depth,
    cached_event_count, uptime_sec, last_heartbeat_at, last_sync_at, last_error,
    is_deleted, created_at, updated_at
)
SELECT 'EDGE-01', '1 号边缘节点 · 装卸区 A', 'LOADING_AREA_A', '10.24.1.11', 'ONLINE', true,
       false, 'edge-agent 1.4.2', 'v3.3', 'v3.3',
       'v3.3', 'v3.3', 32, 24,
       32, 46, 41, 82.0, 0,
       1284, 87684, now(), now(), NULL,
       false, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM safety.edge_node WHERE node_code = 'EDGE-01' AND is_deleted = false);

INSERT INTO safety.edge_node (
    node_code, node_name, area_code, ip_address, node_status_code, cloud_connected,
    autonomy_active, agent_version, active_rule_version, expected_rule_version,
    active_fence_version, expected_fence_version, clock_offset_ms, latency_ms,
    cpu_usage_pct, memory_usage_pct, disk_usage_pct, temperature_c, queue_depth,
    cached_event_count, uptime_sec, last_heartbeat_at, last_sync_at, last_error,
    is_deleted, created_at, updated_at
)
SELECT 'EDGE-02', '2 号边缘节点 · 车辆通道', 'VEHICLE_LANE', '10.24.1.12', 'ONLINE', true,
       false, 'edge-agent 1.4.2', 'v3.3', 'v3.3',
       'v3.3', 'v3.3', 28, 31,
       38, 52, 47, 64.0, 0,
       1036, 87436, now(), now(), NULL,
       false, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM safety.edge_node WHERE node_code = 'EDGE-02' AND is_deleted = false);

INSERT INTO safety.edge_node (
    node_code, node_name, area_code, ip_address, node_status_code, cloud_connected,
    autonomy_active, agent_version, active_rule_version, expected_rule_version,
    active_fence_version, expected_fence_version, clock_offset_ms, latency_ms,
    cpu_usage_pct, memory_usage_pct, disk_usage_pct, temperature_c, queue_depth,
    cached_event_count, uptime_sec, last_heartbeat_at, last_sync_at, last_error,
    is_deleted, created_at, updated_at
)
SELECT 'EDGE-03', '3 号边缘节点 · 翻箱机区', 'CONTAINER_TIPPER_AREA', '10.24.1.13', 'ONLINE', true,
       false, 'edge-agent 1.4.2', 'v3.3', 'v3.3',
       'v3.3', 'v3.3', 35, 28,
       44, 58, 68, 57.0, 0,
       1512, 87912, now(), now(), NULL,
       false, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM safety.edge_node WHERE node_code = 'EDGE-03' AND is_deleted = false);

INSERT INTO safety.edge_node (
    node_code, node_name, area_code, ip_address, node_status_code, cloud_connected,
    autonomy_active, agent_version, active_rule_version, expected_rule_version,
    active_fence_version, expected_fence_version, clock_offset_ms, latency_ms,
    cpu_usage_pct, memory_usage_pct, disk_usage_pct, temperature_c, queue_depth,
    cached_event_count, uptime_sec, last_heartbeat_at, last_sync_at, last_error,
    is_deleted, created_at, updated_at
)
SELECT 'EDGE-04', '4 号边缘节点 · 龙门吊作业区', 'GANTRY_CRANE_AREA', '10.24.1.14', 'ONLINE', true,
       false, 'edge-agent 1.4.2', 'v3.3', 'v3.3',
       'v3.3', 'v3.3', 25, 22,
       29, 41, 36, 49.0, 0,
       902, 87302, now(), now(), NULL,
       false, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM safety.edge_node WHERE node_code = 'EDGE-04' AND is_deleted = false);
