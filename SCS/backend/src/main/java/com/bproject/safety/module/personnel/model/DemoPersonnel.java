package com.bproject.safety.module.personnel.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Backend Demo 阶段的轻量人员模型（非最终人员主数据模型）。
 * 字段与前端 types/personnel.ts 的 PersonnelRecord 对齐，另补 braceletId / state /
 * 规范时间戳与活动告警摘要，保证移除前端 Mock 后页面视觉不变。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DemoPersonnel {

    public String id;
    public String jobNo;
    public String name;
    public String team;
    public String area;
    /** 手环编号（前端 bracelet 字段）。 */
    public String braceletId;
    /** 电量百分比。 */
    public int battery;
    /** 人员在岗状态机器 code（ONLINE/OFFLINE，权威）；中文由 {@link #getStatus()} 派生。 */
    public String statusCode;
    /** 手环状态机器 code（ONLINE/OFFLINE/LOW_BATTERY，权威）；中文由 {@link #getBraceletStatus()} 派生。 */
    public String braceletStatusCode;
    /** 定位质量：优秀 / 良好 / 较低 / 无信号（遥测展示值）。 */
    public String positioningQuality;
    /** 业务风险机器 code（NORMAL/ATTENTION/HIGH，权威）；中文由 {@link #getRisk()} 派生。 */
    public String riskCode;
    /** 地图状态：normal / warning / danger / offline。 */
    public String state;
    /** 相对安全地图百分比坐标。 */
    public double x;
    public double y;
    /** 展示用坐标文案。 */
    public String coordinate;
    public double distanceToday;
    public int alertsToday;
    public String lastUpdated;
    public OffsetDateTime updatedAt;
    /** 关联的未关闭告警编号摘要（不复制完整 Alert）。 */
    public List<String> activeAlertIds;

    /** 种子基线（用于异常模拟后恢复，均为机器 code）。 */
    public transient int baseBattery;
    public transient String baseBraceletStatusCode;
    public transient String basePositioningQuality;
    public transient String baseStatusCode;
    public transient String baseRiskCode;
    public transient String baseState;
    public transient String baseArea;
    public transient double baseX;
    public transient double baseY;

    /** 在岗状态中文标签（由 statusCode 派生，API 兼容）。 */
    @com.fasterxml.jackson.annotation.JsonProperty("status")
    public String getStatus() {
        return PersonStatuses.label(statusCode);
    }

    /** 手环状态中文标签（由 braceletStatusCode 派生，API 兼容）。 */
    @com.fasterxml.jackson.annotation.JsonProperty("braceletStatus")
    public String getBraceletStatus() {
        return BraceletStatuses.label(braceletStatusCode);
    }

    /** 业务风险中文标签（由 riskCode 派生，API 兼容）。 */
    @com.fasterxml.jackson.annotation.JsonProperty("risk")
    public String getRisk() {
        return PersonRiskLevels.label(riskCode);
    }

    public DemoPersonnel snapshotBaseline() {
        this.baseBattery = battery;
        this.baseBraceletStatusCode = braceletStatusCode;
        this.basePositioningQuality = positioningQuality;
        this.baseStatusCode = statusCode;
        this.baseRiskCode = riskCode;
        this.baseState = state;
        this.baseArea = area;
        this.baseX = x;
        this.baseY = y;
        return this;
    }

    /**
     * 整体副本。activeAlertIds 为请求级投影列表需拷贝；transient 基线字段（base*）一并复制，
     * 保证“异常模拟 → save 往返 → restore 演示”不丢基线。
     * Repository 的 copy-on-read/write 依赖本方法切断内部对象引用泄漏。
     */
    public DemoPersonnel copy() {
        DemoPersonnel p = new DemoPersonnel();
        p.id = id;
        p.jobNo = jobNo;
        p.name = name;
        p.team = team;
        p.area = area;
        p.braceletId = braceletId;
        p.battery = battery;
        p.statusCode = statusCode;
        p.braceletStatusCode = braceletStatusCode;
        p.positioningQuality = positioningQuality;
        p.riskCode = riskCode;
        p.state = state;
        p.x = x;
        p.y = y;
        p.coordinate = coordinate;
        p.distanceToday = distanceToday;
        p.alertsToday = alertsToday;
        p.lastUpdated = lastUpdated;
        p.updatedAt = updatedAt;
        p.activeAlertIds = activeAlertIds == null ? null : new java.util.ArrayList<>(activeAlertIds);
        p.baseBattery = baseBattery;
        p.baseBraceletStatusCode = baseBraceletStatusCode;
        p.basePositioningQuality = basePositioningQuality;
        p.baseStatusCode = baseStatusCode;
        p.baseRiskCode = baseRiskCode;
        p.baseState = baseState;
        p.baseArea = baseArea;
        p.baseX = baseX;
        p.baseY = baseY;
        return p;
    }
}
