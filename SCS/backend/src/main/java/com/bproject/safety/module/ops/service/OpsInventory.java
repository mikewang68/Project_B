package com.bproject.safety.module.ops.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 运维设备 / 接口 Demo 台账（SIMULATED：不连真实设备与中间件）。
 *
 * <p>数量对齐前端原 mock：24 摄像头 / 8 雷达 / 12 定位基站 / 6 PLC / 10 声光报警器；
 * 6 条接口链路（规则下发 / 数据上报 / 数字孪生 / PLC 控制 / 视频流 / 权限服务）。
 * 仅维护运维自身的设备 / 接口状态，不复制 Alert / Rule / Fence 业务数据。</p>
 */
@Component
public class OpsInventory {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 设备状态：online / offline / fault。 */
    public static final String ONLINE = "online";
    public static final String OFFLINE = "offline";
    public static final String FAULT = "fault";

    private final Map<String, OpsDevice> devices = new LinkedHashMap<>();
    private final Map<String, OpsInterface> interfaces = new LinkedHashMap<>();
    private final Clock clock;

    public OpsInventory(Clock clock) {
        this.clock = clock;
        seed();
    }

    public record OpsDevice(String id, String name, String type, String category, String area,
                            String edgeNodeId, String status, OffsetDateTime lastSeen,
                            Integer latencyMs, Integer health, String issue) {
        public OpsDevice withStatus(String status, String issue, OffsetDateTime lastSeen) {
            return new OpsDevice(id, name, type, category, area, edgeNodeId, status, lastSeen,
                    latencyMs, health, issue);
        }
    }

    public record OpsInterface(String id, String name, String type, String status, Integer latencyMs,
                               Double successRate, OffsetDateTime lastCheck, String message) {
        public OpsInterface with(String status, Integer latencyMs, Double successRate,
                                 OffsetDateTime lastCheck, String message) {
            return new OpsInterface(id, name, type, status, latencyMs, successRate, lastCheck, message);
        }
    }

    public synchronized List<OpsDevice> devices() {
        return devices.values().stream().sorted(Comparator.comparing(d -> d.id)).toList();
    }

    public synchronized Optional<OpsDevice> device(String id) {
        return Optional.ofNullable(devices.get(id));
    }

    public synchronized OpsDevice saveDevice(OpsDevice device) {
        devices.put(device.id(), device);
        return device;
    }

    public synchronized List<OpsInterface> interfaces() {
        return new ArrayList<>(interfaces.values());
    }

    public synchronized void reset() {
        devices.clear();
        interfaces.clear();
        seed();
    }

    private void seed() {
        OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZONE));
        String[] nodes = {"EDGE-01", "EDGE-02", "EDGE-03", "EDGE-04"};
        String[] areas = {"装卸区 A", "车辆通道", "翻箱机作业区", "龙门吊作业区"};
        addDevices("CAM", "摄像头", "视频感知", 24, nodes, areas, now);
        addDevices("RAD", "雷达", "测距感知", 8, nodes, areas, now);
        addDevices("LOC", "定位基站", "人员定位", 12, nodes, areas, now);
        addDevices("PLC", "PLC 控制器", "设备控制", 6, nodes, areas, now);
        addDevices("ALM", "声光报警器", "现场联动", 10, nodes, areas, now);

        putInterface(new OpsInterface("IF-SCHED", "规则下发链路", "规则引擎", "normal", 36, 99.98, now, "最近下发成功"));
        putInterface(new OpsInterface("IF-DATA", "现场数据上报", "数据通道", "normal", 42, 99.95, now, "数据上报正常"));
        putInterface(new OpsInterface("IF-TWIN", "数字孪生同步", "孪生服务", "normal", 58, 99.90, now, "孪生数据同步正常"));
        putInterface(new OpsInterface("IF-PLC", "PLC 控制链路", "设备控制", "normal", 31, 99.99, now, "PLC 指令通道正常"));
        putInterface(new OpsInterface("IF-VIDEO", "视频流接入", "视频服务", "normal", 120, 99.60, now, "视频流稳定"));
        putInterface(new OpsInterface("IF-AUTH", "权限服务", "基础服务", "normal", 25, 100.0, now, "鉴权服务正常"));
    }

    private void addDevices(String prefix, String typeName, String category, int count,
                            String[] nodes, String[] areas, OffsetDateTime now) {
        for (int i = 1; i <= count; i++) {
            int idx = (i - 1) % nodes.length;
            String id = prefix + "-" + String.format("%02d", i);
            int latency = 20 + (i * 7) % 60;
            int health = 92 + (i * 3) % 8;
            OpsDevice d = new OpsDevice(id, typeName + " " + i, typeName, category,
                    areas[idx], nodes[idx], ONLINE, now, latency, health, null);
            devices.put(id, d);
        }
    }

    private void putInterface(OpsInterface itf) {
        interfaces.put(itf.id(), itf);
    }
}
