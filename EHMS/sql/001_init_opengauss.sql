-- 由openGauss管理员连接EHM业务数据库后执行。
-- 本脚本只创建EHM自己的schema、聚合存储表和索引，不修改其他系统对象。
CREATE SCHEMA IF NOT EXISTS ehm;

CREATE TABLE IF NOT EXISTS ehm.aggregate_store (
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(192) NOT NULL,
    payload TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ehm_aggregate_store PRIMARY KEY (aggregate_type, aggregate_id)
);

CREATE INDEX IF NOT EXISTS idx_ehm_aggregate_updated
    ON ehm.aggregate_store (aggregate_type, updated_at DESC);

COMMENT ON TABLE ehm.aggregate_store IS
    'EHM服务器联调版领域聚合存储；后续可按模块渐进拆分为规范化业务表';
