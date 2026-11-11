-- 004: 规则冲突忽略决策持久化（2026-11-12）
-- 前置：001/002/003 已应用。幂等，可重复执行。
-- 语义：规则冲突检查（同区域同参数不同阈值）支持"忽略"决策并持久化；
--       忽略后的配对在 conflict-check 结果中排除，审计保留操作人。

BEGIN;

CREATE TABLE IF NOT EXISTS safety.rule_conflict_ignore (
    rule_a      varchar(48) NOT NULL,
    rule_b      varchar(48) NOT NULL,
    param       varchar(96) NOT NULL,
    ignored_by  varchar(64),
    ignored_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_rule_conflict_ignore PRIMARY KEY (rule_a, rule_b, param)
);
COMMENT ON TABLE safety.rule_conflict_ignore IS '规则冲突忽略决策：conflict-check 排除已忽略配对；按 (rule_a, rule_b, param) 幂等';

COMMIT;
