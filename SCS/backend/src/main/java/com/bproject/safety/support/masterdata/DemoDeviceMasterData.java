package com.bproject.safety.support.masterdata;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Backend Demo 阶段设备主数据的唯一权威来源（静态身份信息）。
 *
 * <p>只保存设备的稳定身份 / 静态属性：deviceCode、name、type、areaCode、category。
 * 运行态（online、speed、risk、cpu、queueDepth、lastSeen 等）不属于主数据，
 * 仍由各领域 Repository（AI Camera 视图、Collision Repository、EdgeNode Repository、
 * OpsInventory）维护。</p>
 *
 * <p>目的：消除 AI Camera Seeder、Collision 设备台账、OpsInventory、EdgeNode 台账
 * 各自硬编码同一设备 code/name/area 的“设备主数据双源”问题。区域一律引用
 * {@link DemoMasterData} 的稳定 areaCode，不重复书写中文字符串关联。</p>
 *
 * <p><b>本类是 Demo Canonical Master Data，不是正式生产设备主数据。</b></p>
 */
@Component
public class DemoDeviceMasterData {

    /** 设备静态身份。 */
    public record DeviceIdentity(String code, String name, String type, String areaCode,
                                 String areaName, String category) {
    }

    public static final String CAT_CAMERA = "视频感知";
    public static final String CAT_COLLISION = "防碰撞设备";
    public static final String CAT_EDGE = "边缘节点";

    private final DemoMasterData masterData;
    private final List<DeviceIdentity> cameras;
    private final List<DeviceIdentity> collisionDevices;
    private final List<DeviceIdentity> edgeNodes;
    private final Map<String, DeviceIdentity> byCode;

    public DemoDeviceMasterData(DemoMasterData masterData) {
        this.masterData = masterData;
        this.cameras = List.of(
                camera("CAM-01", "装卸区 B 球机", "球机", DemoMasterData.AREA_LOADING_B),
                camera("CAM-02", "龙门吊下枪机", "枪机", DemoMasterData.AREA_GANTRY_CRANE),
                camera("CAM-03", "装卸区 A 球机", "球机", DemoMasterData.AREA_LOADING_A),
                camera("CAM-04", "翻箱机区枪机", "枪机", DemoMasterData.AREA_TIPPER),
                camera("CAM-05", "铁路线 B 枪机", "枪机", DemoMasterData.AREA_RAILWAY_LINE_B),
                camera("CAM-06", "箱区通道球机", "球机", DemoMasterData.AREA_LANE_C),
                camera("CAM-07", "装卸区 B 球机", "球机", DemoMasterData.AREA_LOADING_B),
                camera("CAM-08", "维修通道枪机", "枪机", DemoMasterData.AREA_MAINTENANCE_LANE));
        this.collisionDevices = List.of(
                collision("VEH-07", "转运车辆 07", "转运车辆", DemoMasterData.AREA_VEHICLE_LANE),
                collision("VEH-08", "转运车辆 08", "转运车辆", DemoMasterData.AREA_LOADING_B),
                collision("TIP-02", "翻箱机 02", "翻箱机", DemoMasterData.AREA_TIPPER),
                collision("CRANE-01", "龙门吊 01", "龙门吊", DemoMasterData.AREA_GANTRY_CRANE));
        this.edgeNodes = List.of(
                edge("EDGE-01", "1 号边缘节点 · 装卸区 A", DemoMasterData.AREA_LOADING_A),
                edge("EDGE-02", "2 号边缘节点 · 车辆通道", DemoMasterData.AREA_VEHICLE_LANE),
                edge("EDGE-03", "3 号边缘节点 · 翻箱机区", DemoMasterData.AREA_TIPPER),
                edge("EDGE-04", "4 号边缘节点 · 龙门吊作业区", DemoMasterData.AREA_GANTRY_CRANE));
        this.byCode = concatAll().stream()
                .collect(Collectors.toUnmodifiableMap(DeviceIdentity::code, Function.identity()));
    }

    private List<DeviceIdentity> concatAll() {
        java.util.List<DeviceIdentity> all = new java.util.ArrayList<>();
        all.addAll(cameras);
        all.addAll(collisionDevices);
        all.addAll(edgeNodes);
        return List.copyOf(all);
    }

    private DeviceIdentity camera(String code, String name, String type, String areaCode) {
        return new DeviceIdentity(code, name, type, areaCode, masterData.areas().stream()
                .filter(a -> a.code().equals(areaCode)).map(DemoMasterData.DemoArea::name)
                .findFirst().orElse(null), CAT_CAMERA);
    }

    private DeviceIdentity collision(String code, String name, String type, String areaCode) {
        return new DeviceIdentity(code, name, type, areaCode, masterData.areas().stream()
                .filter(a -> a.code().equals(areaCode)).map(DemoMasterData.DemoArea::name)
                .findFirst().orElse(null), CAT_COLLISION);
    }

    private DeviceIdentity edge(String code, String name, String areaCode) {
        return new DeviceIdentity(code, name, "边缘节点", areaCode, masterData.areas().stream()
                .filter(a -> a.code().equals(areaCode)).map(DemoMasterData.DemoArea::name)
                .findFirst().orElse(null), CAT_EDGE);
    }

    /** 8 路 AI 摄像头静态台账。 */
    public List<DeviceIdentity> cameras() {
        return cameras;
    }

    /** 4 台防碰撞相关设备 / 机械静态台账。 */
    public List<DeviceIdentity> collisionDevices() {
        return collisionDevices;
    }

    /** 4 个边缘节点静态台账。 */
    public List<DeviceIdentity> edgeNodes() {
        return edgeNodes;
    }

    public Optional<DeviceIdentity> device(String code) {
        return Optional.ofNullable(byCode.get(code));
    }
}
