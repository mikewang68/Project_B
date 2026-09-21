package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.EdgeNodeStatuses;
import com.bproject.safety.module.ops.model.EdgeSeedVersions;
import com.bproject.safety.support.demo.DemoResettableStore;
import com.bproject.safety.support.masterdata.DemoDeviceMasterData;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * 进程内边缘节点台账：EDGE-01 ~ EDGE-04（SIMULATED EDGE AUTONOMY）。
 *
 * <p>Phase B：copy-on-read / copy-on-write——find/save 均经过 {@link DemoEdgeNode#copy()}，
 * 不再提供 findMutable；Service 必须走 load → mutate → explicit save。
 * 灌种由 DemoSeedInitializer 在 app.demo.seed-enabled=true 时统一执行。</p>
 */
@Repository
@Profile("!server")
public class InMemoryEdgeNodeRepository implements EdgeNodeRepository, DemoResettableStore {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ConcurrentHashMap<String, DemoEdgeNode> store = new ConcurrentHashMap<>();
    private final Clock clock;
    private final DemoDeviceMasterData deviceMasterData;

    public InMemoryEdgeNodeRepository(Clock clock, DemoDeviceMasterData deviceMasterData) {
        this.clock = clock;
        this.deviceMasterData = deviceMasterData;
        // 不在构造器隐式灌种；由 DemoSeedInitializer 在 app.demo.seed-enabled=true 时统一初始化。
    }

    /** 恢复 4 个初始节点（DemoResettableStore，仅 Demo 种子初始化器 / 测试调用）。 */
    @Override
    public void resetDemoData() {
        store.clear();
        OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZONE));
        store.put("EDGE-01", node("EDGE-01", "10.24.1.11",
                32, 46, 41, 24, 82, now, 1284));
        store.put("EDGE-02", node("EDGE-02", "10.24.1.12",
                38, 52, 47, 31, 64, now, 1036));
        store.put("EDGE-03", node("EDGE-03", "10.24.1.13",
                44, 58, 68, 28, 57, now, 1512));
        store.put("EDGE-04", node("EDGE-04", "10.24.1.14",
                29, 41, 36, 22, 49, now, 902));
    }

    /** 节点 name/area 来自设备主数据，IP 及运行态由本仓库维护。 */
    private DemoEdgeNode node(String id, String ip,
                              int cpu, int mem, int disk, int latency, int temp,
                              OffsetDateTime now, int localCount) {
        DemoDeviceMasterData.DeviceIdentity identity = deviceMasterData.device(id)
                .orElseThrow(() -> new IllegalStateException("缺少边缘节点主数据：" + id));
        return node(id, identity.name(), identity.areaName(), ip,
                cpu, mem, disk, latency, temp, now, localCount);
    }

    private DemoEdgeNode node(String id, String name, String area, String ip,
                              int cpu, int mem, int disk, int latency, int temp,
                              OffsetDateTime now, int localCount) {
        DemoEdgeNode n = new DemoEdgeNode();
        n.id = id;
        n.name = name;
        n.area = area;
        n.ip = ip;
        n.status = EdgeNodeStatuses.ONLINE;
        n.cloudConnected = true;
        n.autonomyActive = false;
        n.lastHeartbeat = now;
        n.latencyMs = latency;
        n.cpuUsage = cpu;
        n.memoryUsage = mem;
        n.diskUsage = disk;
        n.temperature = temp;
        n.queueDepth = 0;
        n.cachedEventCount = localCount;
        n.activeRuleVersion = EdgeSeedVersions.RULE_VERSION;
        n.expectedRuleVersion = EdgeSeedVersions.RULE_VERSION;
        n.activeFenceVersion = EdgeSeedVersions.FENCE_VERSION;
        n.expectedFenceVersion = EdgeSeedVersions.FENCE_VERSION;
        n.clockOffsetMs = 32L;
        n.agentVersion = "edge-agent 1.4.2";
        n.uptimeSec = 86_400L + localCount;
        n.lastSyncAt = now;
        n.lastError = null;
        n.cacheParts = List.of(
                new DemoEdgeNode.CachePart("事件缓存", disk - 3),
                new DemoEdgeNode.CachePart("视频证据缓存", disk + 4),
                new DemoEdgeNode.CachePart("日志空间", disk - 9));
        return n;
    }

    @Override
    public List<DemoEdgeNode> findAll() {
        return store.values().stream().map(DemoEdgeNode::copy)
                .sorted(Comparator.comparing(n -> n.id)).toList();
    }

    @Override
    public Optional<DemoEdgeNode> findById(String id) {
        DemoEdgeNode n = store.get(id);
        return n == null ? Optional.empty() : Optional.of(n.copy());
    }

    @Override
    public DemoEdgeNode save(DemoEdgeNode node) {
        DemoEdgeNode persisted = node.copy();
        store.put(node.id, persisted);
        return persisted.copy();
    }

    @Override
    public long count() {
        return store.size();
    }
}
