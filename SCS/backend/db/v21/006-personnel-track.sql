-- 006: 人员低频历史轨迹（R-S03 演示用）
-- 前置：001/002 已应用。本文件幂等，可重复执行。
-- 边界：只存 10 秒降采样的“关键轨迹”，不把 1~5Hz 原始定位逐点写 openGauss；
--       正式大规模时序应迁 openGemini。轨迹行不新增主数据，人员编码先经 scoped 台账校验。

BEGIN;

CREATE SEQUENCE IF NOT EXISTS safety.seq_safety_personnel_track START 1;

CREATE TABLE IF NOT EXISTS safety.safety_personnel_track (
    id              BIGINT       NOT NULL DEFAULT nextval('safety.seq_safety_personnel_track'),
    personnel_code  VARCHAR(32)  NOT NULL,
    pos_x           NUMERIC(8,2) NOT NULL,
    pos_y           NUMERIC(8,2) NOT NULL,
    area_code       VARCHAR(48),
    source          VARCHAR(24)  NOT NULL DEFAULT 'uwb',
    recorded_at     TIMESTAMPTZ  NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_safety_personnel_track PRIMARY KEY (id)
);

COMMENT ON TABLE safety.safety_personnel_track IS '人员低频历史轨迹样本：10 秒降采样；实时位置仍在内存，不作为主数据源';
COMMENT ON COLUMN safety.safety_personnel_track.recorded_at IS '采样时间（当前由服务接收时间代理，网关提供源时间后可替换）';

CREATE INDEX IF NOT EXISTS idx_personnel_track_person_time
    ON safety.safety_personnel_track (personnel_code, recorded_at DESC);

COMMIT;
