package com.bproject.safety.module.fence.repository;

import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.FencePoint;
import com.bproject.safety.module.fence.model.FenceStatuses;
import com.bproject.safety.support.demo.DemoResettableStore;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 内存围栏台账：4 条 Demo 围栏，与 frontend/src/views/FenceManagementView.vue 旧内联数据对齐。
 *
 * <p>Phase B：copy-on-read / copy-on-write——find/save 均经过 {@link DemoFence#copy()}，
 * 修改 find 返回的对象（含 polygon / nodes）但不调用 save 不会影响仓储内部状态。</p>
 */
@Repository
public class InMemoryFenceRepository implements FenceRepository, DemoResettableStore {

    private final ConcurrentHashMap<String, DemoFence> store = new ConcurrentHashMap<>();

    public InMemoryFenceRepository() {
        // 不在构造器隐式灌种；由 DemoSeedInitializer 在 app.demo.seed-enabled=true 时统一初始化。
    }

    /** 重新灌入种子（DemoResettableStore，仅 Demo 种子初始化器 / 测试调用）。 */
    @Override
    public void resetDemoData() {
        store.clear();
        seed().forEach(f -> store.put(f.id, f.copy()));
    }

    private List<DemoFence> seed() {
        OffsetDateTime now = OffsetDateTime.now();
        return List.of(
                fence("FENCE-001", "龙门吊动态禁区", "危险区域", "danger", "龙门吊作业区", "紧急",
                        "v3.2", FenceStatuses.EFFECTIVE, "2026-08-20 08:00", "长期有效",
                        "装卸一班、设备维保班", "安全主管 王海",
                        List.of(p(38, 5), p(69, 5), p(69, 40), p(38, 40)), now),
                fence("FENCE-002", "翻箱机安全区", "设备区域", "normal", "翻箱机区", "一般",
                        "v2.5", FenceStatuses.EFFECTIVE, "2026-08-22 09:30", "长期有效",
                        "设备维保班", "安全主管 王海",
                        List.of(p(65, 64), p(95, 64), p(95, 94), p(65, 94)), now),
                fence("FENCE-003", "临时检修区", "临时围栏", "temporary", "临时施工区域", "一般",
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
        // 种子中文风险仅用于装配，落模型统一转机器 code；status 已为 FenceStatuses code。
        f.riskCode = com.bproject.safety.module.alert.model.RiskLevels.normalize(risk);
        f.version = version;
        f.statusCode = status;
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
        return store.values().stream().map(DemoFence::copy)
                .sorted(Comparator.comparing(f -> f.id)).toList();
    }

    @Override
    public Optional<DemoFence> findById(String id) {
        DemoFence f = store.get(id);
        return f == null ? Optional.empty() : Optional.of(f.copy());
    }

    @Override
    public DemoFence save(DemoFence fence) {
        DemoFence persisted = fence.copy();
        store.put(fence.id, persisted);
        return persisted.copy();
    }

    @Override
    public long count() {
        return store.size();
    }
}
