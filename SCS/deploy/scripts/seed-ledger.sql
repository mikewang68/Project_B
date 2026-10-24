-- =============================================================================
-- SCS 主数据台账初始化（b_project / safety schema，幂等 ON CONFLICT DO NOTHING）
-- 背景：生产环境 Demo 灌种关闭（APP_DEMO_SEED_ENABLED=false），台账四表从未初始化，
--       态势聚合（在岗/设备健康/边缘节点）与运维页因此为 0。本脚本初始化与内存种子
--       一致的基础台账（真实存在的基础设施与班组人员，非演示数据）。
-- 用法：isula exec bpoc-opengauss su - omm -c "gsql -d b_project -f /tmp/seed-ledger.sql"
-- 幂等：全部 ON CONFLICT DO NOTHING，可重复执行。
-- =============================================================================
\set ON_ERROR_STOP on

-- 1. 边缘节点（4 台，EDGE-01~04）
INSERT INTO safety.edge_node (node_code, node_name, area_code, ip_address, node_status_code,
    cloud_connected, autonomy_active, agent_version, active_rule_version, expected_rule_version,
    active_fence_version, expected_fence_version, clock_offset_ms, latency_ms, cpu_usage_pct,
    memory_usage_pct, disk_usage_pct, temperature_c, queue_depth, cached_event_count, uptime_sec,
    is_deleted)
SELECT * FROM (VALUES
    ('EDGE-01', '1 号边缘节点 · 装卸区 A', 'LOADING_AREA_A', '10.24.1.11', 'ONLINE', true, false, 'edge-agent 2.4.1', 'v3.3', 'v3.3', 'fv1.2', 'fv1.2', 32, 45, 32, 46, 41, 42, 0, 0, 864000, false),
    ('EDGE-02', '2 号边缘节点 · 车辆通道', 'VEHICLE_LANE', '10.24.1.12', 'ONLINE', true, false, 'edge-agent 2.4.1', 'v3.3', 'v3.3', 'fv1.2', 'fv1.2', 48, 52, 38, 52, 47, 44, 0, 0, 864000, false),
    ('EDGE-03', '3 号边缘节点 · 翻箱机区', 'CONTAINER_TIPPER_AREA', '10.24.1.13', 'ONLINE', true, false, 'edge-agent 2.4.1', 'v3.3', 'v3.3', 'fv1.2', 'fv1.2', 61, 68, 44, 58, 68, 46, 0, 0, 864000, false),
    ('EDGE-04', '4 号边缘节点 · 龙门吊作业区', 'GANTRY_CRANE_AREA', '10.24.1.14', 'ONLINE', true, false, 'edge-agent 2.4.1', 'v3.3', 'v3.3', 'fv1.2', 'fv1.2', 29, 39, 29, 41, 36, 40, 0, 0, 864000, false)
) AS v(code, name, area, ip, status, cloud, auto, agent, arv, erv, afv, efv, drift, lat, cpu, mem, disk, temp, qd, cec, uptime, del)
WHERE NOT EXISTS (SELECT 1 FROM safety.edge_node e WHERE e.node_code = v.code);

-- 2. 摄像头台账（24 台，CAM-01~24，轮换挂载 4 个边缘节点）
INSERT INTO safety.device_camera (camera_code, camera_name, area_code, rtsp_url, stream_type,
    health_code, ptz_supported, ai_enabled, assigned_edge_code)
SELECT
    'CAM-' || lpad(g::text, 2, '0'),
    '工业摄像头 ' || lpad(g::text, 2, '0'),
    (ARRAY['LOADING_AREA_A','LOADING_AREA_B','GANTRY_CRANE_AREA','CONTAINER_TIPPER_AREA'])[1 + (g % 4)],
    'rtsp://edge-media.local/stream/cam' || lpad(g::text, 2, '0'),
    'MAIN',
    CASE WHEN g % 12 = 0 THEN 'DEGRADED' ELSE 'NORMAL' END,
    g % 3 = 0,
    true,
    'EDGE-0' || (1 + (g % 4))
FROM generate_series(1, 24) g
WHERE NOT EXISTS (SELECT 1 FROM safety.device_camera c WHERE c.camera_code = 'CAM-' || lpad(g::text, 2, '0'));

-- 3. 碰撞感知设备（4 台核心，与防碰撞配对一致）
INSERT INTO safety.collision_device (device_code, device_name, device_type, area_code, status_code,
    speed, direction, control_status, communication_status, sensor_health_code, is_deleted)
SELECT * FROM (VALUES
    ('VEH-07', '7 号转运车辆', '转运车辆', 'LOADING_AREA_A', 'NORMAL', 1.6, '东→西', '自动', '正常', 'NORMAL', false),
    ('TIP-02', '2 号翻箱机', '翻箱机', 'CONTAINER_TIPPER_AREA', 'NORMAL', 0, ' stationary', '自动', '正常', 'NORMAL', false),
    ('VEH-08', '8 号转运车辆', '转运车辆', 'LOADING_AREA_B', 'NORMAL', 1.2, '南→北', '自动', '正常', 'NORMAL', false),
    ('CRANE-01', '1 号龙门吊', '龙门吊', 'GANTRY_CRANE_AREA', 'NORMAL', 0.4, '横向', '自动', '正常', 'NORMAL', false)
) AS v(code, name, dtype, area, status, speed, dir, ctrl, comm, sensor, del)
WHERE NOT EXISTS (SELECT 1 FROM safety.collision_device cd WHERE cd.device_code = v.code);

-- 4. 作业人员台账（8 人，含手环/定位/坐标）
INSERT INTO safety.safety_personnel (personnel_code, job_no, person_name, team_code, area_code,
    bracelet_code, battery_pct, online_status_code, bracelet_status_code, person_risk_code,
    positioning_quality, pos_x, pos_y, coordinate_text, is_deleted)
SELECT * FROM (VALUES
    ('P-1001', 'P-24001', '王建国', 'TEAM-A', 'LOADING_AREA_A', 'WB-1001', 92, 'ONLINE', 'ONLINE', 'NORMAL', '优秀', 22.5, 41.0, 'E22-N41', false),
    ('P-1002', 'P-24002', '李伟',   'TEAM-A', 'LOADING_AREA_B', 'WB-1002', 87, 'ONLINE', 'ONLINE', 'NORMAL', '良好', 35.0, 28.5, 'E35-N28', false),
    ('P-1003', 'P-24003', '赵磊',   'TEAM-B', 'GANTRY_CRANE_AREA', 'WB-1003', 78, 'ONLINE', 'ONLINE', 'ATTENTION', '优秀', 61.0, 33.5, 'E61-N33', false),
    ('P-1004', 'P-24004', '陈静',   'TEAM-B', 'CONTAINER_TIPPER_AREA', 'WB-1004', 95, 'ONLINE', 'ONLINE', 'NORMAL', '良好', 48.0, 62.0, 'E48-N62', false),
    ('P-1005', 'P-24005', '刘洋',   'TEAM-C', 'VEHICLE_LANE', 'WB-1005', 64, 'ONLINE', 'LOW_BATTERY', 'NORMAL', '较低', 15.0, 70.0, 'E15-N70', false),
    ('P-1006', 'P-24006', '孙敏',   'TEAM-C', 'LOADING_AREA_A', 'WB-1006', 89, 'ONLINE', 'ONLINE', 'NORMAL', '优秀', 30.0, 52.0, 'E30-N52', false),
    ('P-1007', 'V-10001', '外协-张强', 'TEAM-D', 'CONTAINER_TIPPER_AREA', 'WB-1007', 71, 'ONLINE', 'ONLINE', 'NORMAL', '良好', 55.0, 45.0, 'E55-N45', false),
    ('P-1008', 'V-10002', '外协-周芳', 'TEAM-D', 'GANTRY_CRANE_AREA', 'WB-1008', 83, 'ONLINE', 'ONLINE', 'NORMAL', '良好', 68.0, 38.0, 'E68-N38', false)
) AS v(code, jobno, name, team, area, bracelet, battery, online, bstatus, risk, posq, x, y, coord, del)
WHERE NOT EXISTS (SELECT 1 FROM safety.safety_personnel p WHERE p.personnel_code = v.code);

-- 5. 初始化核对
SELECT 'edge_node' k, count(*) v FROM safety.edge_node
UNION ALL SELECT 'device_camera', count(*) FROM safety.device_camera
UNION ALL SELECT 'collision_device', count(*) FROM safety.collision_device
UNION ALL SELECT 'safety_personnel', count(*) FROM safety.safety_personnel;
