package com.bproject.safety.module.ops.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 边缘节点 Demo 模型（SIMULATED EDGE AUTONOMY）。
 *
 * <p>当前只有一个 Spring Boot 进程，这里的“边缘节点”是云边架构行为模拟：
 * 不代表真实部署了边缘计算平台 / 边缘 Agent。字段覆盖任务书第五节要求，
 * 另加自治标记、围栏版本对账、恢复阶段时间线等本阶段演示所需字段。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DemoEdgeNode {

    public String id;
    public String name;
    /** 负责区域。 */
    public String area;
    /** 演示 IP 台账。 */
    public String ip;
    /** ONLINE / DEGRADED / OFFLINE / RECOVERING / ERROR，见 {@link EdgeNodeStatuses}。 */
    public String status;
    /** 与中心平台的云连接是否正常（false 时进入本地自治）。 */
    public boolean cloudConnected;
    /** 边缘自治是否激活：云连接中断后仍继续本地判定与本地联动。 */
    public boolean autonomyActive;

    public OffsetDateTime lastHeartbeat;
    /** 云边链路延迟 ms。 */
    public Integer latencyMs;
    public Integer cpuUsage;
    public Integer memoryUsage;
    /** 缓存 / 磁盘占用 %。 */
    public Integer diskUsage;
    public Integer temperature;

    /** 离线队列深度（待补传事件数），由队列实时汇总。 */
    public int queueDepth;
    /** 累计缓存事件数（本地事件计数，不清零）。 */
    public int cachedEventCount;

    /** 边缘当前运行的规则版本（离线期间冻结，审计不可回改）。 */
    public String activeRuleVersion;
    /** 平台期望（最新已生效）规则版本，由 RuleRepository 实时计算。 */
    public String expectedRuleVersion;
    /** 边缘当前围栏版本（与规则版本独立，不强行合并概念）。 */
    public String activeFenceVersion;
    /** 平台期望围栏版本。 */
    public String expectedFenceVersion;

    /**
     * 节点时钟相对平台的偏差 ms（Demo 模拟，不实现真实 NTP）。
     * abs(clockOffsetMs) &gt; {@link #CLOCK_DRIFT_THRESHOLD_MS} 标记 CLOCK_DRIFT。
     */
    public Long clockOffsetMs;
    /** Demo 时钟漂移阈值：1000ms。 */
    public static final long CLOCK_DRIFT_THRESHOLD_MS = 1000L;

    public String agentVersion;
    public long uptimeSec;
    public OffsetDateTime lastSyncAt;
    public String lastError;

    /** 恢复流程时间线（仅 RECOVERING 期间有值）。 */
    public List<RecoveryPhase> recoveryPhases = new ArrayList<>();
    /** 当前恢复阶段 key（见 {@link RecoveryPhases}），无恢复流程时为 null。 */
    public String recoveryPhase;

    /** 缓存分区占用（事件 / 视频证据 / 日志，Demo 展示用）。 */
    public List<CachePart> cacheParts = new ArrayList<>();

    public boolean clockDrift() {
        return clockOffsetMs != null && Math.abs(clockOffsetMs) > CLOCK_DRIFT_THRESHOLD_MS;
    }

    public boolean ruleMismatch() {
        return activeRuleVersion != null && expectedRuleVersion != null
                && !activeRuleVersion.equals(expectedRuleVersion);
    }

    public DemoEdgeNode copy() {
        DemoEdgeNode n = new DemoEdgeNode();
        n.id = id;
        n.name = name;
        n.area = area;
        n.ip = ip;
        n.status = status;
        n.cloudConnected = cloudConnected;
        n.autonomyActive = autonomyActive;
        n.lastHeartbeat = lastHeartbeat;
        n.latencyMs = latencyMs;
        n.cpuUsage = cpuUsage;
        n.memoryUsage = memoryUsage;
        n.diskUsage = diskUsage;
        n.temperature = temperature;
        n.queueDepth = queueDepth;
        n.cachedEventCount = cachedEventCount;
        n.activeRuleVersion = activeRuleVersion;
        n.expectedRuleVersion = expectedRuleVersion;
        n.activeFenceVersion = activeFenceVersion;
        n.expectedFenceVersion = expectedFenceVersion;
        n.clockOffsetMs = clockOffsetMs;
        n.agentVersion = agentVersion;
        n.uptimeSec = uptimeSec;
        n.lastSyncAt = lastSyncAt;
        n.lastError = lastError;
        n.recoveryPhases = recoveryPartsCopy();
        n.recoveryPhase = recoveryPhase;
        n.cacheParts = cacheParts == null ? new ArrayList<>() : cacheParts.stream().map(CachePart::copy).toList();
        return n;
    }

    private List<RecoveryPhase> recoveryPartsCopy() {
        List<RecoveryPhase> list = new ArrayList<>();
        if (recoveryPhases != null) {
            recoveryPhases.forEach(p -> list.add(p.copy()));
        }
        return list;
    }

    /** 缓存分区。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CachePart {
        public String label;
        public Integer percent;

        public CachePart() {
        }

        public CachePart(String label, Integer percent) {
            this.label = label;
            this.percent = percent;
        }

        public CachePart copy() {
            return new CachePart(label, percent);
        }
    }
}
