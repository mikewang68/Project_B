package com.bproject.safety.module.ai.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/** AI 模块请求。操作主体由服务端会话确定，设备接入字段按持久化边界校验。 */
public final class AiRequests {

    private AiRequests() {
    }

    /** 确认违规。 */
    public record ReviewRequest(String reviewer) {
    }

    /** 标记误报。 */
    public record FalsePositiveRequest(String reason, String reviewer, String note) {
    }

    /** 暂不确定。 */
    public record UncertainRequest(String reviewer) {
    }

    /**
     * AI 派单（无关联 Alert 时先创建 Alert 再复用 Alert 派单主链）。
     * assigneeId 为用户 code（USR-xxx，权威）；assignee 为旧版姓名兼容字段。
     */
    public record AiAssignRequest(String assignee, String assigneeId, String priority, String note, String reviewer) {
    }

    /** 开始处理 / 关闭。 */
    public record ProcessRequest(String operator, String note) {
    }

    /**
     * 模拟事件：kind=new（默认，新 AI 事件）/ low-confidence（低置信度翻越护栏）/ camera-fault（摄像头异常）。
     */
    public record SimulateRequest(String kind) {
    }

    /**
     * 边端真实 AI 视觉检测上报请求体（来自 Python/C++ 边缘计算节点）。
     */
    public record AiIngestRequest(
            @Size(max=48) String eventType,
            @Size(max=32) String cameraCode,
            @Size(max=96) String cameraName,
            @Size(max=48) String areaCode,
            @DecimalMin("0") @DecimalMax("100") Double confidence,
            @DecimalMin("0") @DecimalMax("100") Double threshold,
            @DecimalMin("0") @DecimalMax("999999.99") Double durationSec,
            @Size(max=64) String modelCode,
            @Size(max=32) String riskCode,
            @Size(max=128) String sceneType,
            @Size(max=40) String ruleCode,
            @Size(max=4096) String judgeText,
            @Size(max=2048) String snapshotUrl,
            @Size(max=128) java.util.List<com.bproject.safety.module.ai.model.AiBox> boxes,
            @Size(max=32) String relatedPerson,
            @Size(max=32) String relatedDevice,
            java.time.OffsetDateTime occurredAt
    ) {
    }

    /** 触发边缘 AI 推理请求。 */
    public record TriggerEdgeRequest(String scenario, String cameraCode, String personName) {
    }
}
