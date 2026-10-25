-- SCS P0-3 / Step 7: safety.ai_model 建表（幂等，可重复执行）
-- 与 backend/src/main/resources/db/design/openGauss-schema-final-draft.sql 6.3 节保持一致
CREATE TABLE IF NOT EXISTS safety.ai_model (
    model_code           VARCHAR(64)  NOT NULL,
    model_name           VARCHAR(128) NOT NULL,
    task_type            VARCHAR(64)  NOT NULL,
    version_no           VARCHAR(24),
    engine_type          VARCHAR(32),
    accuracy_map         NUMERIC(6,4),
    latency_ms           NUMERIC(8,2),
    fps                  NUMERIC(6,2),
    confidence_threshold NUMERIC(4,2) NOT NULL DEFAULT 0.70,
    iou_threshold        NUMERIC(4,2) NOT NULL DEFAULT 0.45,
    is_selected          BOOLEAN      NOT NULL DEFAULT false,
    status_code          VARCHAR(16)  NOT NULL DEFAULT 'READY',
    weight_size          VARCHAR(32),
    supported_classes    JSONB        NOT NULL DEFAULT '[]',
    description          TEXT,
    is_deleted           BOOLEAN      NOT NULL DEFAULT false,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_model PRIMARY KEY (model_code)
);
COMMENT ON TABLE safety.ai_model IS 'AI 工业模型配置台账（在线选用热切换与参数调优持久化；软删除）';

CREATE OR REPLACE VIEW safety.v_ai_model AS
    SELECT * FROM safety.ai_model WHERE is_deleted = false;

CREATE OR REPLACE FUNCTION safety.fn_prevent_physical_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Physical DELETE is strictly prohibited on table %. Use logical deletion (is_deleted = true or status transition) instead.', TG_TABLE_NAME;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_delete_ai_model ON safety.ai_model;
CREATE TRIGGER trg_prevent_delete_ai_model BEFORE DELETE ON safety.ai_model
    FOR EACH ROW EXECUTE PROCEDURE safety.fn_prevent_physical_delete();
