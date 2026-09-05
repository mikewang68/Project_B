package com.bproject.safety.module.fence.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Backend Demo 阶段的轻量电子围栏模型（非 GIS 正式模型，polygon 沿用前端百分比坐标）。
 * 字段与前端 types/fence.ts 的 FenceRecord 对齐。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DemoFence {

    public String id;
    public String name;
    /** 危险区域 / 设备区域 / 临时围栏 / 预警区域 / 授权区域。 */
    public String kind;
    /** danger / warning / normal / temporary（前端配色口径）。 */
    public String tone;
    public String area;
    /** 风险等级：一般 / 严重 / 紧急。 */
    public String riskLevel;
    public String version;
    public String status;
    public String effectiveAt;
    public String expiresAt;
    public String teams;
    public String approver;
    public int edgeSynced;
    public int edgeTotal = 4;
    public List<FencePoint> polygon = new ArrayList<>();
    public List<EdgeNode> nodes = new ArrayList<>();
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static List<EdgeNode> pendingNodes() {
        return new ArrayList<>(List.of(
                new EdgeNode("EDGE-01", EdgeNode.PENDING),
                new EdgeNode("EDGE-02", EdgeNode.PENDING),
                new EdgeNode("EDGE-03", EdgeNode.PENDING),
                new EdgeNode("EDGE-04", EdgeNode.PENDING)));
    }

    public static List<EdgeNode> successNodes() {
        return new ArrayList<>(List.of(
                new EdgeNode("EDGE-01", EdgeNode.SUCCESS),
                new EdgeNode("EDGE-02", EdgeNode.SUCCESS),
                new EdgeNode("EDGE-03", EdgeNode.SUCCESS),
                new EdgeNode("EDGE-04", EdgeNode.SUCCESS)));
    }
}
