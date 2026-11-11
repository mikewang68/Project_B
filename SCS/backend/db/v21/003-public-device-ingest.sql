-- 003: 公共模拟设备数据接收箱与设备绑定（董博涵公共底座交接包，2026-11-11）
-- 前置：001/002 已应用。本文件幂等，可重复执行。
-- 语义：public_device_inbox 按 event_id 幂等（broker ACK 前先落箱，业务映射同事务推进状态）；
--       public_device_binding 把公共设备 ID 绑到 SCS 业务域与对象码，未知对象按现有 ingest 哲学 ignored。

BEGIN;

CREATE TABLE IF NOT EXISTS safety.public_device_inbox (
    event_id      varchar(128) PRIMARY KEY,
    committed_ms  bigint       NOT NULL,
    wire_sha256   varchar(128),
    payload       jsonb        NOT NULL,
    status        varchar(16)  NOT NULL DEFAULT 'RECEIVED',
    last_error    varchar(512),
    received_at   timestamptz  NOT NULL DEFAULT now(),
    processed_at  timestamptz,
    CONSTRAINT public_device_inbox_status_chk CHECK (status IN ('RECEIVED','PROCESSED','FAILED'))
);
COMMENT ON TABLE safety.public_device_inbox IS '公共底座已提交事件接收箱：event_id 幂等，业务映射失败保留原事件待补偿';
CREATE INDEX IF NOT EXISTS idx_public_device_inbox_status ON safety.public_device_inbox (status, committed_ms);

CREATE TABLE IF NOT EXISTS safety.public_device_binding (
    device_id        varchar(64) PRIMARY KEY,
    template_id      varchar(64) NOT NULL,
    scs_domain       varchar(16) NOT NULL,
    scs_object_code  varchar(64) NOT NULL,
    enabled          boolean     NOT NULL DEFAULT true,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT public_device_binding_domain_chk CHECK (scs_domain IN ('RADAR','UWB','AI','DEVICE','ZONE','OBSERVE'))
);
COMMENT ON TABLE safety.public_device_binding IS '公共模拟设备 → SCS 业务域与对象码绑定；未知对象按 ingest 哲学 ignored，不自动建档';

CREATE TABLE IF NOT EXISTS safety.public_device_state (
    state_key  varchar(64) PRIMARY KEY,
    state_value text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE safety.public_device_state IS '公共数据接收进度元数据（如 HTTP 补读游标）';

-- 绑定种子：与 catalog.json WB-DEVICE-TEMPLATES-v1 对齐；对象码 = SCS 现有台账（VEH/TIP/CRANE/LOADING/CAM/P-1xxx）
-- 注：openGauss 不支持 INSERT ON CONFLICT，用 NOT EXISTS 守卫保证幂等
INSERT INTO safety.public_device_binding (device_id, template_id, scs_domain, scs_object_code)
SELECT 'SIM-0ece1fee47f641be93d4ac53', 'scs-radar', 'RADAR', 'VEH-07'
WHERE NOT EXISTS (SELECT 1 FROM safety.public_device_binding WHERE device_id='SIM-0ece1fee47f641be93d4ac53');
INSERT INTO safety.public_device_binding (device_id, template_id, scs_domain, scs_object_code)
SELECT 'SIM-142e73df50a24723a70c4eae', 'scs-aibox', 'AI', 'CAM-01'
WHERE NOT EXISTS (SELECT 1 FROM safety.public_device_binding WHERE device_id='SIM-142e73df50a24723a70c4eae');
INSERT INTO safety.public_device_binding (device_id, template_id, scs_domain, scs_object_code)
SELECT 'SIM-14dcba2c643b48f084b55b7e', 'scs-uwb', 'UWB', 'P-1001'
WHERE NOT EXISTS (SELECT 1 FROM safety.public_device_binding WHERE device_id='SIM-14dcba2c643b48f084b55b7e');
INSERT INTO safety.public_device_binding (device_id, template_id, scs_domain, scs_object_code)
SELECT 'SIM-471ece2b2ec742939729173d', 'scs-tipper', 'DEVICE', 'TIP-02'
WHERE NOT EXISTS (SELECT 1 FROM safety.public_device_binding WHERE device_id='SIM-471ece2b2ec742939729173d');
INSERT INTO safety.public_device_binding (device_id, template_id, scs_domain, scs_object_code)
SELECT 'SIM-4bf65976b5754fd89d903232', 'scs-limit-switch', 'ZONE', 'LOADING-A'
WHERE NOT EXISTS (SELECT 1 FROM safety.public_device_binding WHERE device_id='SIM-4bf65976b5754fd89d903232');
INSERT INTO safety.public_device_binding (device_id, template_id, scs_domain, scs_object_code)
SELECT 'SIM-aed34d3f7bb84082a061bf7a', 'scs-crane', 'DEVICE', 'CRANE-01'
WHERE NOT EXISTS (SELECT 1 FROM safety.public_device_binding WHERE device_id='SIM-aed34d3f7bb84082a061bf7a');
INSERT INTO safety.public_device_binding (device_id, template_id, scs_domain, scs_object_code)
SELECT 'SIM-af1935235c6b4adbbfa7a2cc', 'scs-alarm-light', 'OBSERVE', 'ALARM-LIGHT-01'
WHERE NOT EXISTS (SELECT 1 FROM safety.public_device_binding WHERE device_id='SIM-af1935235c6b4adbbfa7a2cc');
INSERT INTO safety.public_device_binding (device_id, template_id, scs_domain, scs_object_code)
SELECT 'SIM-f0f11ffa302b424f80ab9d30', 'scs-position-sensor', 'ZONE', 'LOADING-B'
WHERE NOT EXISTS (SELECT 1 FROM safety.public_device_binding WHERE device_id='SIM-f0f11ffa302b424f80ab9d30');

COMMIT;
