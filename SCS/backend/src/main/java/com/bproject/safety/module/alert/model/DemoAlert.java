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
    /**
     * 风险等级机器 code（权威值），见 {@link RiskLevels}（NORMAL/WARNING/SEVERE/URGENT）。
     * 中文展示通过 {@link #getRisk()} 派生，禁止在业务判断中使用中文字面量。
     */
    public String riskCode;
    public String eventType;
    /** 展示用时间（HH:mm:ss，仅展示；权威时间为 {@link #occurredAt}）。 */
    public String time;
    public String area;
    public String target;
    /** 来源：人员安全 / 设备防碰撞 / AI违规 / 设备异常 / 系统异常。 */
    public String source;
    /** 当前状态机器 code（权威值），见 {@link AlertStatuses}。 */
    public String statusCode;

    /** 责任人用户 code（USR-xxx，正式业务关联值，F-02）；未派单为 null。 */
    public String assigneeUserCode;
    /**
     * 责任人姓名快照（派单时刻从主数据解析，仅用于展示，不能作为关联依据）。
     * 未派单为“待分配”。
     */
    public String assignee;

    /**
     * 判定 / 配置来源类型（F-07 provenance）：FENCE / RULE / AI_MODEL / COLLISION / MANUAL / EDGE 等。
     * 与 {@link #ruleId}/{@link #ruleVersion} 区分：人员越界依据围栏时不得把围栏版本写成规则版本。
     */
    public String decisionSourceType;
    /** 判定来源实体 code（如 FENCE-003 / RULE-COL-001 / 模型 code）。 */
    public String decisionSourceCode;
    /** 判定来源版本（如围栏 v1.2 / 模型版本），语义随 {@link #decisionSourceType}。 */
    public String decisionSourceVersion;

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
    /** 升级前风险等级机器 code（见 {@link RiskLevels}）；中文展示通过 {@link #getUpgradedFrom()} 派生。 */
    public String upgradedFromCode;
    /** 最近一次风险升级前的等级机器 code（B5-01 规范命名）。 */
    public String previousRiskLevelCode;

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

    /**
     * 边缘断网补传元数据（任务书第二十六节，仅补传 Alert 非空；不新建 EdgeAlert，仍是现有 Alert）。
     * source 仍保留业务来源（人员安全 / 设备防碰撞），origin 统一标记 EDGE_REPLAY。
     */
    public EdgeReplayMeta edgeReplay;

    /** 边缘补传来源标记（普通实时告警为 null，补传告警为 EDGE_REPLAY）。 */
    public String origin;

    /** 边缘补传 Demo 元数据。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EdgeReplayMeta {
        public String edgeNodeId;
        public String offlineEventId;
        /** 是否为离线期间发生（补传告警恒为 true）。 */
        public boolean offlineOccurred;
        /** 补传延迟秒数 = 平台接收时间 - 边缘发生时间。 */
        public Long syncDelaySec;
        /** 离线事件发生时使用的规则版本（审计，不回改）。 */
        public String ruleVersionUsed;
        /** 平台接收到补传的时间。 */
        public OffsetDateTime syncedAt;

        /** 深拷贝（copy-on-read/write：仓储返回的副本之间不得共享可变子对象）。 */
        public EdgeReplayMeta copy() {
            EdgeReplayMeta m = new EdgeReplayMeta();
            m.edgeNodeId = edgeNodeId;
            m.offlineEventId = offlineEventId;
            m.offlineOccurred = offlineOccurred;
            m.syncDelaySec = syncDelaySec;
            m.ruleVersionUsed = ruleVersionUsed;
            m.syncedAt = syncedAt;
            return m;
        }
    }

    // ---------- 中文展示派生（API 兼容：JSON 仍输出 status / risk / upgradedFrom 中文） ----------

    /** 当前状态中文标签（由 statusCode 派生）。 */
    @com.fasterxml.jackson.annotation.JsonProperty("status")
    public String getStatus() {
        return AlertStatuses.label(statusCode);
    }

    /** 风险等级中文标签（由 riskCode 派生）。 */
    @com.fasterxml.jackson.annotation.JsonProperty("risk")
    public String getRisk() {
        return RiskLevels.label(riskCode);
    }

    /** 升级前风险等级中文标签（由 upgradedFromCode 派生）。 */
    @com.fasterxml.jackson.annotation.JsonProperty("upgradedFrom")
    public String getUpgradedFrom() {
        return RiskLevels.label(upgradedFromCode);
    }

    public DemoAlert copy() {
        DemoAlert a = new DemoAlert();
        a.id = id;
        a.title = title;
        a.riskCode = riskCode;
        a.eventType = eventType;
        a.time = time;
        a.area = area;
        a.target = target;
        a.source = source;
        a.statusCode = statusCode;
        a.assigneeUserCode = assigneeUserCode;
        a.assignee = assignee;
        a.decisionSourceType = decisionSourceType;
        a.decisionSourceCode = decisionSourceCode;
        a.decisionSourceVersion = decisionSourceVersion;
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
        a.treatment = treatment == null ? null
                : new TreatmentRecord(treatment.measures() == null ? null
                        : List.copyOf(treatment.measures()),
                        treatment.result(), treatment.attachment(), treatment.note(),
                        treatment.submitTime(), treatment.handler());
        a.reviewUser = reviewUser;
        a.reviewTime = reviewTime;
        a.reviewNote = reviewNote;
        a.upgradedFromCode = upgradedFromCode;
        a.previousRiskLevelCode = previousRiskLevelCode;
        a.mobileStage = mobileStage;
        a.acceptedAt = acceptedAt;
        a.arrivedAt = arrivedAt;
        a.occurredAt = occurredAt;
        a.slaDeadline = slaDeadline;
        a.updatedAt = updatedAt;
        a.edgeReplay = edgeReplay == null ? null : edgeReplay.copy();
        a.origin = origin;
        return a;
    }
}
