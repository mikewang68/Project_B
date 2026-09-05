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
    /** 安全 / 预警 / 严重 / 紧急 / 待确认（由配对实时风险装饰）。 */
    public String risk;
    public String relatedEquipmentId;
    public String relatedEquipment;
    public String latestAlertId;
    public String latestAlert;
    /** 地图百分比坐标。 */
    public double x;
    public double y;
}
