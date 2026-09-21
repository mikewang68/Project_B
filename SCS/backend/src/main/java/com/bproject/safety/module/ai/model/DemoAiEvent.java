package com.bproject.safety.module.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Demo AI 识别事件（轻量内存模型，非最终领域实体；字段与前端 types/ai.ts 的 AiEvent 对齐）。
 *
 * <p>AIEvent 与 Alert 不是同一对象：AI 负责识别 / 证据 / 置信度 / 复核 / 派单入口，
 * 只有人工「确认违规」后才通过 AlertService 创建关联 Alert，进入统一处置主链。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DemoAiEvent {

    public String id;
    public String type;
    public String camera;
    public String cameraName;
    public String area;
    public double confidence;
    public double durationSec;
    public String model;
    public double threshold;
    /** 展示用 HH:mm:ss */
    public String time;
    /** AI 复核状态机器 code（权威），见 {@link AiReviewStatuses}；中文由 {@link #getStatus()} 派生。 */
    public String statusCode;
    /** AI 风险机器 code（LOW/MEDIUM/HIGH，权威）；中文由 {@link #getRisk()} 派生。 */
    public String riskCode;
    /** 正常 / 画面质量下降 / 离线（摄像头健康展示值，非 AI 风险等级）。 */
    public String health;
    /** helmet / fence / intrusion / linger / camera */
    public String scene;
    public List<AiBox> boxes = new ArrayList<>();
    public String rule;
    public String relatedPerson;
    public String relatedDevice;
    public String judgeText;

    public String reviewer;
    public String reviewTime;
    public String falseReason;
    /** 责任人用户 code（USR-xxx，权威关联，F-02）。 */
    public String assigneeUserCode;
    public String assignee;
    public String assignmentPriority;
    public String processStatus;
    public String assignmentNote;

    public List<AiTimelineNode> timeline = new ArrayList<>();

    /** 确认违规后关联的统一告警 ID（Alert 主链）；误报永远为空。 */
    public String linkedAlertId;

    public OffsetDateTime occurredAt;
    public OffsetDateTime updatedAt;

    /** 前端卡片 Fade 动画标记（不参与业务）。 */
    @JsonProperty("fresh")
    public Boolean fresh;

    /** AI 复核状态中文标签（由 statusCode 派生，API 兼容）。 */
    @JsonProperty("status")
    public String getStatus() {
        return AiReviewStatuses.label(statusCode);
    }

    /** AI 风险中文标签（由 riskCode 派生，API 兼容）。 */
    @JsonProperty("risk")
    public String getRisk() {
        return AiRiskLevels.label(riskCode);
    }

    public DemoAiEvent copy() {
        DemoAiEvent c = new DemoAiEvent();
        c.id = id;
        c.type = type;
        c.camera = camera;
        c.cameraName = cameraName;
        c.area = area;
        c.confidence = confidence;
        c.durationSec = durationSec;
        c.model = model;
        c.threshold = threshold;
        c.time = time;
        c.statusCode = statusCode;
        c.riskCode = riskCode;
        c.health = health;
        c.scene = scene;
        c.boxes = boxes == null ? new ArrayList<>() : new ArrayList<>(boxes);
        c.rule = rule;
        c.relatedPerson = relatedPerson;
        c.relatedDevice = relatedDevice;
        c.judgeText = judgeText;
        c.reviewer = reviewer;
        c.reviewTime = reviewTime;
        c.falseReason = falseReason;
        c.assigneeUserCode = assigneeUserCode;
        c.assignee = assignee;
        c.assignmentPriority = assignmentPriority;
        c.processStatus = processStatus;
        c.assignmentNote = assignmentNote;
        c.timeline = timeline == null ? new ArrayList<>() : new ArrayList<>(timeline);
        c.linkedAlertId = linkedAlertId;
        c.occurredAt = occurredAt;
        c.updatedAt = updatedAt;
        c.fresh = fresh;
        return c;
    }
}
