package com.bproject.safety.module.fence.repository;

import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.EdgeNode;
import com.bproject.safety.module.fence.model.FencePoint;
import com.bproject.safety.module.fence.model.FenceStatuses;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** 内存围栏台账：4 条 Demo 围栏，与 frontend/src/views/FenceManagementView.vue 旧内联数据对齐。 */
@Repository
public class InMemoryFenceRepository implements FenceRepository {

    private final ConcurrentHashMap<String, DemoFence> store = new ConcurrentHashMap<>();

    public InMemoryFenceRepository() {
        reset();
    }

    /** 重新灌入种子（测试隔离用）。 */
    public void reset() {
        store.clear();
        seed().forEach(f -> store.put(f.id, f));
    }

    private List<DemoFence> seed() {
        OffsetDateTime now = OffsetDateTime.now();
        return List.of(
                fence("FENCE-001", "龙门吊动态禁区", "危险区域", "danger", "龙门吊作业区", "紧急",
                        "v3.2", FenceStatuses.EFFECTIVE, "2026-08-20 08:00", "长期有效",
                        "装卸一班、设备保障", "安全主管 王海",
                        List.of(p(38, 5), p(69, 5), p(69, 40), p(38, 40)), now),
                fence("FENCE-002", "翻箱机安全区", "设备区域", "normal", "翻箱机作业区", "一般",
                        "v2.5", FenceStatuses.EFFECTIVE, "2026-08-22 09:30", "长期有效",
                        "设备保障", "安全主管 王海",
                        List.of(p(65, 64), p(95, 64), p(95, 94), p(65, 94)), now),
                fence("FENCE-003", "临时检修区", "临时围栏", "temporary", "临时施工区", "一般",
                        "v1.2", FenceStatuses.TO_PUBLISH, "2026-09-02 23:00", "2026-09-04 06:00",
                        "检修班、外协单位", "李建国",
                        List.of(p(5, 66), p(33, 66), p(33, 95), p(5, 95)), now),
                fence("FENCE-004", "车辆通道缓冲区", "预警区域", "warning", "车辆通道", "预警",
                        "v1.8", FenceStatuses.EFFECTIVE, "2026-08-28 12:00", "长期有效",
                        "装卸一班、装卸二班", "安全主管 王海",
                        List.of(p(31, 45), p(72, 45), p(72, 61), p(31, 61)), now));
    }

    private static FencePoint p(double x, double y) {
        return new FencePoint(x, y);
    }

    private DemoFence fence(String id, String name, String kind, String tone, String area, String risk,
                            String version, String status, String start, String end, String teams,
                            String approver, List<FencePoint> polygon, OffsetDateTime now) {
        DemoFence f = new DemoFence();
        f.id = id;
        f.name = name;
        f.kind = kind;
        f.tone = tone;
        f.area = area;
        f.riskLevel = risk;
        f.version = version;
        f.status = status;
        f.effectiveAt = start;
        f.expiresAt = end;
        f.teams = teams;
        f.approver = approver;
        f.polygon = new java.util.ArrayList<>(polygon);
        f.edgeTotal = 4;
        boolean effective = FenceStatuses.EFFECTIVE.equals(status);
        f.nodes = effective ? DemoFence.successNodes() : DemoFence.pendingNodes();
        f.edgeSynced = effective ? 4 : 0;
        f.createdAt = now;
        f.updatedAt = now;
        return f;
    }

    @Override
    public List<DemoFence> findAll() {
        return store.values().stream().sorted(Comparator.comparing(f -> f.id)).toList();
    }

    @Override
    public java.util.Optional<DemoFence> findById(String id) {
        return java.util.Optional.ofNullable(store.get(id));
    }

    @Override
    public DemoFence save(DemoFence fence) {
        store.put(fence.id, fence);
        return fence;
    }

    @Override
    public long count() {
        return store.size();
    }
}
