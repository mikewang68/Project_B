package com.bproject.safety.module.ops.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 云边恢复流程（任务书第十八节固定顺序）：
 * CONNECTIVITY → CLOCK_RECONCILIATION → RULE_RECONCILIATION → EVENT_REPLAY → FINAL_CHECK → ONLINE。
 *
 * <p>每一步记录状态 / 开始 / 完成时间 / 说明，前端可渲染恢复时间线；
 * 时间、规则对账阶段在发现漂移 / 版本不一致时进入 WAIT，等待对应对账接口推进。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecoveryPhase {

    /** WAIT（未开始）/ RUNNING / DONE / FAILED / BLOCKED（等待人工 / 对账交互）。 */
    public static final String WAIT = "WAIT";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String BLOCKED = "BLOCKED";

    public String key;
    public String label;
    public String status;
    public OffsetDateTime startedAt;
    public OffsetDateTime completedAt;
    public String message;

    public RecoveryPhase() {
    }

    public RecoveryPhase(String key, String label) {
        this.key = key;
        this.label = label;
        this.status = WAIT;
    }

    public RecoveryPhase start(OffsetDateTime at) {
        this.startedAt = at;
        this.status = RUNNING;
        return this;
    }

    public RecoveryPhase complete(OffsetDateTime at, String message) {
        this.completedAt = at;
        this.status = DONE;
        this.message = message;
        return this;
    }

    public RecoveryPhase block(String message) {
        this.status = BLOCKED;
        this.message = message;
        return this;
    }

    public RecoveryPhase fail(String message) {
        this.status = FAILED;
        this.message = message;
        return this;
    }

    public RecoveryPhase copy() {
        RecoveryPhase p = new RecoveryPhase();
        p.key = key;
        p.label = label;
        p.status = status;
        p.startedAt = startedAt;
        p.completedAt = completedAt;
        p.message = message;
        return p;
    }
}
