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
    /** 在线 / 离线（人员在岗状态）。 */
    public String status;
    /** 手环状态：在线 / 离线 / 低电量。 */
    public String braceletStatus;
    /** 定位质量：优秀 / 良好 / 较低 / 无信号。 */
    public String positioningQuality;
    /** 业务风险：正常 / 关注 / 高风险。 */
    public String risk;
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

    /** 种子基线（用于异常模拟后恢复）。 */
    public transient int baseBattery;
    public transient String baseBraceletStatus;
    public transient String basePositioningQuality;
    public transient String baseStatus;
    public transient String baseRisk;
    public transient String baseState;
    public transient String baseArea;
    public transient double baseX;
    public transient double baseY;

    public DemoPersonnel snapshotBaseline() {
        this.baseBattery = battery;
        this.baseBraceletStatus = braceletStatus;
        this.basePositioningQuality = positioningQuality;
        this.baseStatus = status;
        this.baseRisk = risk;
        this.baseState = state;
        this.baseArea = area;
        this.baseX = x;
        this.baseY = y;
        return this;
    }
}
