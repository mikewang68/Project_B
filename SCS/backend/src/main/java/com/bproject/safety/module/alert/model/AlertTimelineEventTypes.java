package com.bproject.safety.module.alert.model;

/**
 * 告警时间线机器事件类型（F-12）。
 *
 * <p>用于统计 / SLA 时长计算，替代过去对中文 text 的关键字匹配；中文展示仍由各节点 text 承载。
 * 集合依据当前 Alert 真实处置流程确定，不覆盖 AI 自身时间线（AI 另有独立时间线）。</p>
 */
public final class AlertTimelineEventTypes {

    /** 风险建单（感知模块 / AI / 边缘补传的首节点）。 */
    public static final String CREATED = "CREATED";
    /** 人工确认事件。 */
    public static final String CONFIRMED = "CONFIRMED";
    /** 派单。 */
    public static final String ASSIGNED = "ASSIGNED";
    /** 转派。 */
    public static final String TRANSFERRED = "TRANSFERRED";
    /** 移动端接单。 */
    public static final String ACCEPTED = "ACCEPTED";
    /** 移动端到场。 */
    public static final String ARRIVED = "ARRIVED";
    /** 开始 / 接单处理。 */
    public static final String STARTED = "STARTED";
    /** 提交处置结果。 */
    public static final String TREATMENT_SUBMITTED = "TREATMENT_SUBMITTED";
    /** 复核通过。 */
    public static final String REVIEW_APPROVED = "REVIEW_APPROVED";
    /** 复核驳回。 */
    public static final String REVIEW_REJECTED = "REVIEW_REJECTED";
    /** 风险升级。 */
    public static final String ESCALATED = "ESCALATED";
    /** 发起联动。 */
    public static final String LINKAGE_STARTED = "LINKAGE_STARTED";
    /** 联动失败。 */
    public static final String LINKAGE_FAILED = "LINKAGE_FAILED";
    /** 人工接管。 */
    public static final String TAKEOVER = "TAKEOVER";
    /** 关闭。 */
    public static final String CLOSED = "CLOSED";
    /** 边缘补传类系统节点（边缘产生 / 入缓存 / 补传等，非人工动作）。 */
    public static final String EDGE_REPLAY = "EDGE_REPLAY";
    /** 通用 / 展示性节点（无法归入上述机器事件时使用，不参与时长统计）。 */
    public static final String NOTE = "NOTE";

    private AlertTimelineEventTypes() {
    }
}
