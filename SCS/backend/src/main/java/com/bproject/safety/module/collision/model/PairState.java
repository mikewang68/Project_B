package com.bproject.safety.module.collision.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备配对实时状态（Backend Demo）：一对设备（如转运车辆↔翻箱机）共享一份距离 / 风险 / 联动状态。
 * 由 Service 单线程语义内修改，不暴露为独立“碰撞告警库”——风险处置仍进入 Alert 主链。
 */
public class PairState {

    public final String currentId;
    public final String relatedId;
    public double distance;
    public double relSpeed;
    public String direction;
    public int radarQuality;
    public String risk;
    public boolean radarDown;
    public boolean deviceStopped;
    public boolean controlFailure;
    public String plcStatus = "待命";
    public int approachIndex;
    public List<DistancePoint> trend = new ArrayList<>();
    public List<CollisionStep> steps = new ArrayList<>();
    public String activeAlertId;
    public OffsetDateTime updatedAt;

    public PairState(String currentId, String relatedId, double initDistance, List<DistancePoint> initTrend) {
        this.currentId = currentId;
        this.relatedId = relatedId;
        this.distance = initDistance;
        this.relSpeed = 1.2;
        this.direction = "接近";
        this.radarQuality = 97;
        this.risk = "安全";
        this.trend = new ArrayList<>(initTrend);
        this.steps = baseSteps();
    }

    public static List<CollisionStep> baseSteps() {
        return new ArrayList<>(List.of(
                new CollisionStep("detect", "风险检测", "waiting", "综合距离与运动趋势"),
                new CollisionStep("alarm", "声光提醒", "waiting", "现场声光设备待命"),
                new CollisionStep("driver", "司机提醒", "waiting", "车载终端待命"),
                new CollisionStep("slow", "减速请求", "waiting", "等待风险等级触发"),
                new CollisionStep("stop", "紧急停机", "waiting", "等待紧急条件触发"),
                new CollisionStep("plc", "PLC 回执", "waiting", "尚未发送控制指令")));
    }
}
