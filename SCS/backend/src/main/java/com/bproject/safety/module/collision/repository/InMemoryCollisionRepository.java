package com.bproject.safety.module.collision.repository;

import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import com.bproject.safety.support.demo.DemoResettableStore;
import com.bproject.safety.support.masterdata.DemoDeviceMasterData;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * 内存防碰撞设备台账：4 台 Demo 设备，与 frontend/src/views/CollisionOverviewView.vue 旧内联数据对齐。
 * 设备身份（code/name/type/area）来自 {@link DemoDeviceMasterData}，本仓库只维护运行态。
 *
 * <p>Phase B：copy-on-read / copy-on-write——find/save 均经过 {@link DemoCollisionDevice#copy()}，
 * 修改 find 返回的对象但不调用 save 不会影响仓储内部状态，语义与未来 JDBC 实现一致。</p>
 */
@Repository
@Profile("!server")
public class InMemoryCollisionRepository implements CollisionRepository, DemoResettableStore {

    private final ConcurrentHashMap<String, DemoCollisionDevice> store = new ConcurrentHashMap<>();
    private final DemoDeviceMasterData deviceMasterData;

    public InMemoryCollisionRepository(DemoDeviceMasterData deviceMasterData) {
        this.deviceMasterData = deviceMasterData;
        // 不在构造器隐式灌种；由 DemoSeedInitializer 在 app.demo.seed-enabled=true 时统一初始化。
    }

    /** 重新灌入种子（DemoResettableStore，仅 Demo 种子初始化器 / 测试调用）。 */
    @Override
    public void resetDemoData() {
        store.clear();
        seed().forEach(d -> store.put(d.id, d.copy()));
    }

    private List<DemoCollisionDevice> seed() {
        return List.of(
                device("VEH-07", "运行中", 6.2, "东北",
                        "自动控制可用", "在线 · 24ms", "正常", "TIP-02", "今日无未处理告警", 39, 53),
                device("VEH-08", "运行中", 4.8, "正北",
                        "自动控制可用", "在线 · 31ms", "正常", "CRANE-01", "10:24 进入预警距离，已自动恢复", 80, 40),
                device("TIP-02", "作业中", 0, "固定工位",
                        "PLC 联锁可用", "在线 · 18ms", "正常", "VEH-07", "今日无未处理告警", 68, 72),
                device("CRANE-01", "作业中", 0, "轨道东西向",
                        "PLC 联锁可用", "在线 · 22ms", "正常", "VEH-08", "09:42 安全区接近预警", 60, 22));
    }

    private DemoCollisionDevice device(String id, String status,
                                       double speed, String direction, String control, String comm,
                                       String radar, String relatedId, String latest, double x, double y) {
        DemoDeviceMasterData.DeviceIdentity identity = deviceMasterData.device(id)
                .orElseThrow(() -> new IllegalStateException("缺少设备主数据：" + id));
        DemoCollisionDevice d = new DemoCollisionDevice();
        d.id = identity.code();
        d.name = identity.name();
        d.type = identity.type();
        d.area = identity.areaName();
        d.status = status;
        d.speed = speed;
        d.direction = direction;
        d.controlStatus = control;
        d.communication = comm;
        d.radarStatus = radar;
        d.riskCode = com.bproject.safety.module.collision.model.CollisionRiskLevels.SAFE;
        d.healthCode = com.bproject.safety.module.collision.model.SensorHealth.NORMAL;
        d.relatedEquipmentId = relatedId;
        d.latestAlert = latest;
        d.x = x;
        d.y = y;
        return d;
    }

    @Override
    public List<DemoCollisionDevice> findAll() {
        return store.values().stream().map(DemoCollisionDevice::copy)
                .sorted(Comparator.comparing(d -> d.id)).toList();
    }

    @Override
    public Optional<DemoCollisionDevice> findById(String id) {
        DemoCollisionDevice d = store.get(id);
        return d == null ? Optional.empty() : Optional.of(d.copy());
    }

    @Override
    public DemoCollisionDevice save(DemoCollisionDevice device) {
        DemoCollisionDevice persisted = device.copy();
        store.put(device.id, persisted);
        return persisted.copy();
    }
}
