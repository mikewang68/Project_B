package com.bproject.safety.module.collision.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Backend Demo 防碰撞设备（转运车辆 / 翻箱机 / 龙门吊），字段对齐前端 types/collision.ts，
 * 另补 x/y 供态势地图使用。非最终设备主数据模型。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DemoCollisionDevice {

    public String id;
    public String name;
    public String type;
    public String area;
    public String status;
    public double speed;
    public String direction;
    public String controlStatus;
    public String communication;
    public String radarStatus;
    public String lastUpdated;
    /** 碰撞风险机器 code（SAFE/WARNING/SEVERE/URGENT，权威）；中文由 {@link #getRisk()} 派生。 */
    public String riskCode;
    /** 雷达 / 感知健康机器 code（NORMAL/UNCERTAIN/RADAR_DOWN）；UNCERTAIN 时风险展示为“待确认”。 */
    public String healthCode;
    public String relatedEquipmentId;
    public String relatedEquipment;
    public String latestAlertId;
    public String latestAlert;
    /** 地图百分比坐标。 */
    public double x;
    public double y;

    /** 风险中文标签：雷达不确定时展示“待确认”（健康态），否则展示碰撞风险等级（API 兼容）。 */
    @com.fasterxml.jackson.annotation.JsonProperty("risk")
    public String getRisk() {
        if (SensorHealth.UNCERTAIN.equals(healthCode) || SensorHealth.RADAR_DOWN.equals(healthCode)) {
            return SensorHealth.label(healthCode);
        }
        return CollisionRiskLevels.label(riskCode);
    }

    /** 感知健康中文标签。 */
    @com.fasterxml.jackson.annotation.JsonProperty("health")
    public String getHealth() {
        return SensorHealth.label(healthCode);
    }

    /**
     * 整体副本（全部为标量字段，直接赋值即可）。
     * Repository 的 copy-on-read/write 依赖本方法：修改返回对象但不调用 save 不得影响仓储内部状态。
     */
    public DemoCollisionDevice copy() {
        DemoCollisionDevice d = new DemoCollisionDevice();
        d.id = id;
        d.name = name;
        d.type = type;
        d.area = area;
        d.status = status;
        d.speed = speed;
        d.direction = direction;
        d.controlStatus = controlStatus;
        d.communication = communication;
        d.radarStatus = radarStatus;
        d.lastUpdated = lastUpdated;
        d.riskCode = riskCode;
        d.healthCode = healthCode;
        d.relatedEquipmentId = relatedEquipmentId;
        d.relatedEquipment = relatedEquipment;
        d.latestAlertId = latestAlertId;
        d.latestAlert = latestAlert;
        d.x = x;
        d.y = y;
        return d;
    }
}
