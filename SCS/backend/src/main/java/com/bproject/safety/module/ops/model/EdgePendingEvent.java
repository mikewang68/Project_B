package com.bproject.safety.module.ops.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 离线边缘事件（任务书第十一 / 二十四 / 三十九 / 四十节）。
 *
 * <p>云连接中断期间，边缘本地判定继续执行、本地联动继续执行，事件进入本队列缓存而
 * <b>不</b>进入云端 AlertRepository、<b>不</b>广播 alert.new；恢复后按顺序幂等补传。
 * 只保留 payloadSummary 必要字段，不存视频 / 图片等大对象。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EdgePendingEvent {

    /** 事件唯一编号（边缘生成，补传幂等主键之一）。 */
    public String eventId;
    public String edgeNodeId;
    /** 事件类型：person-intrusion / collision-risk / ppe-violation / person-stay。 */
    public String eventType;
    /** 业务键（人员 / 设备 / 围栏编号组合，便于追溯）。 */
    public String businessKey;

    /** 边缘实际发生时间（补传生成 Alert 时 occurredAt 必须取它，不能取补传时刻）。 */
    public OffsetDateTime edgeOccurredAt;
    /** 边缘本地接收 / 入缓存时间。 */
    public OffsetDateTime receivedAt;
    /** 平台服务器接收到补传的时间（恢复补传时写入）。 */
    public OffsetDateTime serverReceivedAt;
    /** 补传完成时间。 */
    public OffsetDateTime syncedAt;

    /** 幂等键（与 eventId 共同保证云端只消费一次）。 */
    public String idempotencyKey;
    /** 事件发生时该节点时钟偏差快照（解释发生时间与接收时间差异，不实现真实 NTP）。 */
    public Long clockOffsetAtOccurrence;
    /** 事件发生时使用的规则版本（审计语义，恢复后平台升级也不得回改）。 */
    public String ruleVersionUsed;

    /** 风险等级：一般 / 预警 / 严重 / 紧急。 */
    public String risk;
    /** 必要字段摘要（人员 / 围栏 / 设备 / 风险等级 / 业务 ID），不存大对象。 */
    public PayloadSummary payloadSummary;
    /** 边缘本地联动结果（SIMULATED）。 */
    public EdgeLocalLinkage localLinkage;

    /** PENDING / SYNCING / SYNCED / FAILED / DUPLICATE。 */
    public String status;
    public int retryCount;
    public OffsetDateTime lastRetryAt;
    public String lastError;

    /** 补传成功后关联的云端 AlertId。 */
    public String linkedAlertId;
    /** 补传延迟秒数 = syncedAt - edgeOccurredAt。 */
    public Long syncDelaySec;

    /** 演示用：下一次 replay 强制失败一次（任务书第三十五节故障补传）。 */
    public transient boolean failNextReplay;

    /** 事件必要字段摘要（不存视频 / 图片）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PayloadSummary {
        public String person;
        public String fence;
        public String device;
        public String risk;
        public String businessId;
        public String title;
        public String area;
        /** 结构化补充（如距离 3.6m、PLC 停车指令已执行）。 */
        public String detail;

        public PayloadSummary() {
        }

        public PayloadSummary(String title, String area, String person, String fence, String device,
                              String risk, String businessId, String detail) {
            this.title = title;
            this.area = area;
            this.person = person;
            this.fence = fence;
            this.device = device;
            this.risk = risk;
            this.businessId = businessId;
            this.detail = detail;
        }
    }

    public EdgePendingEvent copy() {
        EdgePendingEvent e = new EdgePendingEvent();
        e.eventId = eventId;
        e.edgeNodeId = edgeNodeId;
        e.eventType = eventType;
        e.businessKey = businessKey;
        e.edgeOccurredAt = edgeOccurredAt;
        e.receivedAt = receivedAt;
        e.serverReceivedAt = serverReceivedAt;
        e.syncedAt = syncedAt;
        e.idempotencyKey = idempotencyKey;
        e.clockOffsetAtOccurrence = clockOffsetAtOccurrence;
        e.ruleVersionUsed = ruleVersionUsed;
        e.risk = risk;
        e.payloadSummary = payloadSummary;
        e.localLinkage = localLinkage;
        e.status = status;
        e.retryCount = retryCount;
        e.lastRetryAt = lastRetryAt;
        e.lastError = lastError;
        e.linkedAlertId = linkedAlertId;
        e.syncDelaySec = syncDelaySec;
        e.failNextReplay = failNextReplay;
        return e;
    }
}
