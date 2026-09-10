package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.EdgeNodeStatuses;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 进程内边缘节点台账：EDGE-01 ~ EDGE-04（SIMULATED EDGE AUTONOMY）。
 * 查询统一返回副本；构造时自播种，测试可调用 {@link #reset(Clock)} 恢复初始状态。
 */
@Repository
public class InMemoryEdgeNodeRepository implements EdgeNodeRepository {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 初始平台规则版本（与 RuleDemoSeeder 中 RULE-PER-001 当前版本一致）。 */
    public static final String SEED_RULE_VERSION = "v3.3";
    public static final String SEED_FENCE_VERSION = "v3.3";

    private final ConcurrentHashMap<String, DemoEdgeNode> store = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryEdgeNodeRepository(Clock clock) {
        this.clock = clock;
        reset(clock);
    }

    /** 恢复 4 个初始节点（测试隔离用）。 */
    public void reset(Clock seedClock) {
        store.clear();
        OffsetDateTime now = OffsetDateTime.now(seedClock.withZone(ZONE));
        store.put("EDGE-01", node("EDGE-01", "1 号边缘节点 · 装卸区 A", "装卸区 A", "10.24.1.11",
                32, 46, 41, 24, 82, now, 1284));
        store.put("EDGE-02", node("EDGE-02", "2 号边缘节点 · 车辆通道", "车辆通道", "10.24.1.12",
                38, 52, 47, 31, 64, now, 1036));
        store.put("EDGE-03", node("EDGE-03", "3 号边缘节点 · 翻箱机区", "翻箱机作业区", "10.24.1.13",
                44, 58, 68, 28, 57, now, 1512));
        store.put("EDGE-04", node("EDGE-04", "4 号边缘节点 · 龙门吊作业区", "龙门吊作业区", "10.24.1.14",
                29, 41, 36, 22, 49, now, 902));
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
        n.activeRuleVersion = SEED_RULE_VERSION;
        n.expectedRuleVersion = SEED_RULE_VERSION;
        n.activeFenceVersion = SEED_FENCE_VERSION;
        n.expectedFenceVersion = SEED_FENCE_VERSION;
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

    /** 供 Service 层在锁内修改后保存（直接持有实例，避免 copy 丢更新）。 */
    public Optional<DemoEdgeNode> findMutable(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public DemoEdgeNode save(DemoEdgeNode node) {
        store.put(node.id, node);
        return node.copy();
    }

    @Override
    public long count() {
        return store.size();
    }
}
