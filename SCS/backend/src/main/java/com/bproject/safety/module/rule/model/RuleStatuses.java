package com.bproject.safety.module.rule.model;

import java.util.List;

/** 规则状态常量（Demo 状态机，不做最终状态机设计）。 */
public final class RuleStatuses {

    private RuleStatuses() {
    }

    public static final String DRAFT = "草稿";
    public static final String REVIEW = "待评审";
    public static final String APPROVED = "已批准";
    public static final String PUBLISHING = "发布中";
    public static final String ACTIVE = "已生效";
    public static final String MISMATCH = "版本异常";
    public static final String DISABLED = "已停用";

    /** 边缘节点同步状态。 */
    public static final String EDGE_SYNCED = "synced";
    public static final String EDGE_SYNCING = "syncing";
    public static final String EDGE_MISMATCH = "mismatch";

    /** 统一风险等级顺序。 */
    public static final List<String> RISK_LEVELS = List.of("一般", "预警", "严重", "紧急");
}
