package com.bproject.safety.module.collision.repository;

import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** 内存防碰撞设备台账：4 台 Demo 设备，与 frontend/src/views/CollisionOverviewView.vue 旧内联数据对齐。 */
@Repository
public class InMemoryCollisionRepository implements CollisionRepository {

    private final ConcurrentHashMap<String, DemoCollisionDevice> store = new ConcurrentHashMap<>();

    public InMemoryCollisionRepository() {
        reset();
    }

    /** 重新灌入种子（测试隔离用）。 */
    public void reset() {
        store.clear();
        seed().forEach(d -> store.put(d.id, d));
    }

    private List<DemoCollisionDevice> seed() {
        return List.of(
                device("VEH-07", "转运车辆 07", "转运车辆", "车辆通道 R-06", "运行中", 6.2, "东北",
                        "自动控制可用", "在线 · 24ms", "正常", "TIP-02", "今日无未处理告警", 39, 53),
                device("VEH-08", "转运车辆 08", "转运车辆", "装卸区 B 通道", "运行中", 4.8, "正北",
                        "自动控制可用", "在线 · 31ms", "正常", "CRANE-01", "10:24 进入预警距离，已自动恢复", 80, 40),
                device("TIP-02", "翻箱机 02", "翻箱机", "翻箱机作业区", "作业中", 0, "固定工位",
                        "PLC 联锁可用", "在线 · 18ms", "正常", "VEH-07", "今日无未处理告警", 68, 72),
                device("CRANE-01", "龙门吊 01", "龙门吊", "龙门吊作业区", "作业中", 0, "轨道东西向",
                        "PLC 联锁可用", "在线 · 22ms", "正常", "VEH-08", "09:42 安全区接近预警", 60, 22));
    }

    private static DemoCollisionDevice device(String id, String name, String type, String area, String status,
                                              double speed, String direction, String control, String comm,
                                              String radar, String relatedId, String latest, double x, double y) {
        DemoCollisionDevice d = new DemoCollisionDevice();
        d.id = id;
        d.name = name;
        d.type = type;
        d.area = area;
        d.status = status;
        d.speed = speed;
        d.direction = direction;
        d.controlStatus = control;
        d.communication = comm;
        d.radarStatus = radar;
        d.risk = "安全";
        d.relatedEquipmentId = relatedId;
        d.latestAlert = latest;
        d.x = x;
        d.y = y;
        return d;
    }

    @Override
    public List<DemoCollisionDevice> findAll() {
        return store.values().stream().sorted(Comparator.comparing(d -> d.id)).toList();
    }

    @Override
    public java.util.Optional<DemoCollisionDevice> findById(String id) {
        return java.util.Optional.ofNullable(store.get(id));
    }

    @Override
    public DemoCollisionDevice save(DemoCollisionDevice device) {
        store.put(device.id, device);
        return device;
    }
}
