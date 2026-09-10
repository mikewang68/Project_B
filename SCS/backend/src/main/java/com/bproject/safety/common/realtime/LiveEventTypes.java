package com.bproject.safety.common.realtime;

/**
 * 统一实时事件类型（LiveEvent.type）。
 *
 * <p>WebSocket 只负责通知客户端“数据发生了变化”，不承载完整业务对象；
 * 前端收到后按 alertId 重新请求 REST 权威数据。</p>
 */
public final class LiveEventTypes {

    private LiveEventTypes() {
    }

    /** 新告警（预留：当前 Backend Demo 无新建告警业务，种子数据不广播） */
    public static final String ALERT_NEW = "alert.new";
    /** 告警通用变更（confirm / start / review-reject / transfer / takeover / accept / arrive） */
    public static final String ALERT_CHANGED = "alert.changed";
    /** 告警派单 */
    public static final String ALERT_ASSIGNED = "alert.assigned";
    /** 提交处置结果 */
    public static final String ALERT_TREATMENT = "alert.treatment";
    /** 复核关闭 */
    public static final String ALERT_CLOSED = "alert.closed";
    /** 事件升级 */
    public static final String ALERT_ESCALATED = "alert.escalated";
    /** 联动状态变化 */
    public static final String ALERT_LINKAGE_CHANGED = "alert.linkage.changed";
    /** AI 识别新事件 */
    public static final String AI_NEW = "ai.new";
    /** AI 事件通用变更（不确定 / 处理中 / 关闭等） */
    public static final String AI_CHANGED = "ai.changed";
    /** AI 复核完成（确认违规 / 误报 / 派单） */
    public static final String AI_REVIEWED = "ai.reviewed";
    /** 系统通知 */
    public static final String SYSTEM_NOTICE = "system.notice";
    /** 人员位置 / 状态变化 */
    public static final String PERSON_MOVED = "person.moved";
    /** 围栏变更（编辑 / 发布 / 版本异常 / 重新下发 / 停用） */
    public static final String FENCE_CHANGED = "fence.changed";
    /** 防碰撞设备状态通用变化 */
    public static final String COLLISION_CHANGED = "collision.changed";
    /** 防碰撞风险等级变化 */
    public static final String COLLISION_RISK_CHANGED = "collision.risk.changed";
    /** 防碰撞联动步骤变化 */
    public static final String COLLISION_LINKAGE_CHANGED = "collision.linkage.changed";
    /** 规则通用变更（新建 / 编辑 / 提交 / 批准 / 驳回 / 回滚 / 停用） */
    public static final String RULE_CHANGED = "rule.changed";
    /** 规则发布完成（边缘节点全部同步成功） */
    public static final String RULE_PUBLISHED = "rule.published";
    /** 规则边缘同步状态变化（版本异常 / 重新下发） */
    public static final String RULE_SYNC_CHANGED = "rule.sync.changed";

    /** 运维：边缘节点状态变化（任务书第二十八节）。 */
    public static final String OPS_NODE_CHANGED = "ops.node.changed";
    /** 运维：规则 / 时间同步状态变化。 */
    public static final String OPS_SYNC_CHANGED = "ops.sync.changed";
    /** 运维：离线队列变化（入队 / 补传 / 失败 / 去重）。 */
    public static final String OPS_QUEUE_CHANGED = "ops.queue.changed";
    /** 运维：恢复流程阶段变化。 */
    public static final String OPS_RECOVERY_CHANGED = "ops.recovery.changed";

    /** 客户端心跳请求 */
    public static final String PING = "ping";
    /** 服务端心跳响应 */
    public static final String PONG = "pong";
}
