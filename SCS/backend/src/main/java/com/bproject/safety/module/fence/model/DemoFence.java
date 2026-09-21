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
    /** 风险等级机器 code（RiskLevels：NORMAL/WARNING/SEVERE/URGENT，权威）；中文由 {@link #getRiskLevel()} 派生。 */
    public String riskCode;
    public String version;
    /** 围栏生命周期状态机器 code（FenceStatuses，权威）；中文由 {@link #getStatus()} 派生。 */
    public String statusCode;
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

    /** 风险等级中文标签（由 riskCode 派生，API 兼容）。 */
    @com.fasterxml.jackson.annotation.JsonProperty("riskLevel")
    public String getRiskLevel() {
        return com.bproject.safety.module.alert.model.RiskLevels.label(riskCode);
    }

    /** 围栏状态中文标签（由 statusCode 派生，API 兼容）。 */
    @com.fasterxml.jackson.annotation.JsonProperty("status")
    public String getStatus() {
        return FenceStatuses.label(statusCode);
    }

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

    /**
     * 整体副本（polygon 的 FencePoint、nodes 的 EdgeNode 均为不可变 record，浅拷贝集合即可）。
     * Repository 的 copy-on-read/write 依赖本方法切断内部对象引用泄漏。
     */
    public DemoFence copy() {
        DemoFence f = new DemoFence();
        f.id = id;
        f.name = name;
        f.kind = kind;
        f.tone = tone;
        f.area = area;
        f.riskCode = riskCode;
        f.version = version;
        f.statusCode = statusCode;
        f.effectiveAt = effectiveAt;
        f.expiresAt = expiresAt;
        f.teams = teams;
        f.approver = approver;
        f.edgeSynced = edgeSynced;
        f.edgeTotal = edgeTotal;
        f.polygon = polygon == null ? new ArrayList<>() : new ArrayList<>(polygon);
        f.nodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
        f.createdAt = createdAt;
        f.updatedAt = updatedAt;
        return f;
    }
}
