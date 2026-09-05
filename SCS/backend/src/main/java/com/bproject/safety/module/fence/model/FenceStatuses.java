package com.bproject.safety.module.fence.model;

/** 围栏生命周期状态（Backend Demo 口径，对齐前端 types/fence.ts，非正式规则状态机）。 */
public final class FenceStatuses {

    private FenceStatuses() {
    }

    public static final String DRAFT = "草稿";
    public static final String TO_REVIEW = "待评审";
    public static final String TO_PUBLISH = "待发布";
    public static final String EFFECTIVE = "已生效";
    public static final String DISABLED = "已停用";
    public static final String MISMATCH = "版本不一致";
}
