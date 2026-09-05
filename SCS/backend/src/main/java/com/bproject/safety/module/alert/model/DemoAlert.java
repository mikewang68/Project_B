package com.bproject.safety.module.alert.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Backend Demo 阶段的轻量告警模型（非最终领域模型；AIEvent 与 Alert 的边界等后续再统一）。
 *
 * <p>字段命名与前端 types/alert.ts 的 AlertEvent 对齐，保证后续移除前端 Mock 时页面视觉不变；
 * 另补 occurredAt / slaDeadline / updatedAt 三个 ISO-8601 规范时间戳。本类为演示期可变模型，
 * 仅由 Service 层在单线程语义内修改（Repository 保证可见性）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DemoAlert {

    /** 事件编号，如 ALM-20260904-001。 */
    public String id;
    public String title;
    /** 风险等级：一般 / 预警 / 严重 / 紧急。 */
    public String risk;
    public String eventType;
    /** 展示用时间（HH:mm:ss，与当前前端 Mock 一致）。 */
    public String time;
    public String area;
    public String target;
    /** 来源：人员安全 / 设备防碰撞 / AI违规 / 设备异常 / 系统异常。 */
    public String source;
    /** 当前状态，见 {@link AlertStatuses}。 */
    public String status;
    public String assignee;

    /**
     * Demo 级风险去重键（Backend Demo 过渡设计）：如 PERSON_INTRUSION:P-ZHAO:FENCE-001、
     * COLLISION:VEH-07:TIP-02。同一去重键存在未关闭 Alert 时不再重复创建，风险升级走原 Alert。
     * AI 复核生成的告警无此键（允许每次确认独立成单）。
     */
    public String dedupKey;

    /** SLA 剩余秒数（负数=已超时；已关闭为 null），每次读取时由 Service 依据 slaDeadline 重算。 */
    public Long slaRemainingSec;
    public String ruleId;
    public String ruleVersion;
    public int durationSec;

    public AlertEvidence evidence;
    public boolean linkageAvailable;
    public List<LinkageStep> linkage = new ArrayList<>();
    public boolean linkageFailed;
    public boolean linkageFinished;
    public boolean takeover;

    public List<TimelineEvent> timeline = new ArrayList<>();

    public String confirmUser;
    public String confirmTime;
    public String acceptTime;
    public String priority;
    public Integer slaLimitMin;
    public TreatmentRecord treatment;
    public String reviewUser;
    public String reviewTime;
    public String reviewNote;
    public String upgradedFrom;

    /**
     * 移动端现场处置阶段（Backend Demo 过渡设计，见 docs/mobile-alert-integration-issues.md）：
     * PENDING / ACCEPTED / ARRIVED / PROCESSING。主状态机 status 不因此改变，
     * 仅用于承接移动端“待接单→已接单→已到场→处理中”与管理端“待处理→处理中”的差异。
     */
    public String mobileStage;
    /** 移动端接单时间（ISO-8601）。 */
    public OffsetDateTime acceptedAt;
    /** 移动端到场时间（ISO-8601）。 */
    public OffsetDateTime arrivedAt;

    /** 规范时间戳（ISO-8601，带时区）。 */
    public OffsetDateTime occurredAt;
    public OffsetDateTime slaDeadline;
    public OffsetDateTime updatedAt;

    public DemoAlert copy() {
        DemoAlert a = new DemoAlert();
        a.id = id;
        a.title = title;
        a.risk = risk;
        a.eventType = eventType;
        a.time = time;
        a.area = area;
        a.target = target;
        a.source = source;
        a.status = status;
        a.assignee = assignee;
        a.dedupKey = dedupKey;
        a.slaRemainingSec = slaRemainingSec;
        a.ruleId = ruleId;
        a.ruleVersion = ruleVersion;
        a.durationSec = durationSec;
        a.evidence = evidence;
        a.linkageAvailable = linkageAvailable;
        a.linkage = new ArrayList<>(linkage);
        a.linkageFailed = linkageFailed;
        a.linkageFinished = linkageFinished;
        a.takeover = takeover;
        a.timeline = new ArrayList<>(timeline);
        a.confirmUser = confirmUser;
        a.confirmTime = confirmTime;
        a.acceptTime = acceptTime;
        a.priority = priority;
        a.slaLimitMin = slaLimitMin;
        a.treatment = treatment;
        a.reviewUser = reviewUser;
        a.reviewTime = reviewTime;
        a.reviewNote = reviewNote;
        a.upgradedFrom = upgradedFrom;
        a.mobileStage = mobileStage;
        a.acceptedAt = acceptedAt;
        a.arrivedAt = arrivedAt;
        a.occurredAt = occurredAt;
        a.slaDeadline = slaDeadline;
        a.updatedAt = updatedAt;
        return a;
    }
}
