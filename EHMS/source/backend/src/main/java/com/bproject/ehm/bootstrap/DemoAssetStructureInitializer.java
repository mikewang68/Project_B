package com.bproject.ehm.bootstrap;

import com.bproject.ehm.asset.domain.model.Component;
import com.bproject.ehm.asset.domain.model.MeasurementPoint;
import com.bproject.ehm.asset.ports.ComponentRepository;
import com.bproject.ehm.asset.ports.MeasurementPointRepository;
import com.bproject.ehm.monitoring.domain.model.PointQualitySnapshot;
import com.bproject.ehm.monitoring.ports.PointQualityRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@org.springframework.stereotype.Component
@Profile("demo")
@Order(100)
public class DemoAssetStructureInitializer implements CommandLineRunner {
    private final ComponentRepository components;
    private final MeasurementPointRepository measurementPoints;
    private final PointQualityRepository pointQuality;

    public DemoAssetStructureInitializer(ComponentRepository components,
                                         MeasurementPointRepository measurementPoints,
                                         PointQualityRepository pointQuality) {
        this.components = components;
        this.measurementPoints = measurementPoints;
        this.pointQuality = pointQuality;
    }

    @Override
    public void run(String... args) {
        if (components.countActiveByAssetCode("GT-01") > 0) return;
        Instant now = Instant.now();
        List<Component> componentSeeds = List.of(
                component("GT01-HOIST", "GT-01", null, "起升机构", "系统/机构", "A类", "主梁小车", now),
                component("GT01-GEARBOX", "GT-01", "GT01-HOIST", "起升减速机", "传动部件", "A类", "起升机构", now),
                component("GT01-MOTOR", "GT-01", "GT01-HOIST", "起升电机", "电气部件", "A类", "起升机构", now),
                component("GT01-BRAKE", "GT-01", "GT01-HOIST", "起升制动器", "安全部件", "A类", "起升机构", now),
                component("GT03-GATEWAY", "GT-03", null, "边缘采集网关", "采集设备", "A类", "电控柜", now),
                component("FX01-HYD", "FX-01", null, "液压执行机构", "液压系统", "A类", "翻转机构", now),
                component("DF01-FILTER", "DF-01", null, "除尘滤袋组件", "环保设备", "B类", "转运站除尘器", now)
        );
        componentSeeds.forEach(components::save);

        List<PointSeed> pointSeeds = List.of(
                new PointSeed("GT01-VIB-RMS", "GT-01", "GT01-GEARBOX", "减速机振动速度RMS", "振动速度", "mm/s", "OPC UA", "ns=2;s=GT01.Hoist.Gearbox.VibRms", 5, 0.0, 7.1, 6.8, 0),
                new PointSeed("GT01-GEAR-TEMP", "GT-01", "GT01-GEARBOX", "减速机轴承温度", "温度", "℃", "OPC UA", "ns=2;s=GT01.Hoist.Gearbox.Temp", 10, -20.0, 75.0, 68.2, 0),
                new PointSeed("GT01-MOTOR-CURRENT", "GT-01", "GT01-MOTOR", "起升电机电流", "电流", "A", "Modbus TCP", "40021", 2, 0.0, 220.0, 162.4, 0),
                new PointSeed("GT01-LOAD", "GT-01", "GT01-HOIST", "起升载荷", "载荷", "t", "OPC UA", "ns=2;s=GT01.Hoist.Load", 1, 0.0, 40.0, 34.6, 0),
                new PointSeed("GT01-BRAKE-TIME", "GT-01", "GT01-BRAKE", "制动响应时间", "响应时间", "s", "MQTT", "ehm/gt01/brake/time", 10, 0.0, 1.8, 1.24, 0),
                new PointSeed("GT03-GW-HEARTBEAT", "GT-03", "GT03-GATEWAY", "网关心跳延迟", "心跳延迟", "ms", "MQTT", "edge/gt03/heartbeat", 5, 0.0, 1000.0, 42.0, 24),
                new PointSeed("GT03-GW-CPU", "GT-03", "GT03-GATEWAY", "网关CPU负载", "CPU负载", "%", "MQTT", "edge/gt03/cpu", 30, 0.0, 95.0, 37.0, 24),
                new PointSeed("GT03-GW-DISK", "GT-03", "GT03-GATEWAY", "断网缓存占用", "磁盘占用", "%", "MQTT", "edge/gt03/disk", 60, 0.0, 90.0, 61.0, 24),
                new PointSeed("FX01-HYD-TEMP", "FX-01", "FX01-HYD", "液压油温", "温度", "℃", "Modbus TCP", "40110", 10, -20.0, 70.0, 59.4, 0),
                new PointSeed("FX01-HYD-PRESS", "FX-01", "FX01-HYD", "液压工作压力", "压力", "MPa", "Modbus TCP", "40112", 2, 0.0, 21.0, 15.8, 0),
                new PointSeed("DF01-DP", "DF-01", "DF01-FILTER", "滤袋前后压差", "压差", "kPa", "Modbus RTU", "slave=3;reg=30018", 10, 0.0, 1.6, 1.8, 0),
                new PointSeed("DF01-FAN-CURRENT", "DF-01", "DF01-FILTER", "引风机电流", "电流", "A", "Modbus RTU", "slave=3;reg=30022", 5, 0.0, 100.0, 71.9, 0)
        );
        for (PointSeed seed : pointSeeds) {
            MeasurementPoint point = measurementPoints.save(MeasurementPoint.create(seed.code(), seed.assetCode(),
                    seed.componentCode(), seed.name(), seed.metric(), seed.unit(), seed.protocol(), seed.address(),
                    seed.intervalSeconds(), seed.lowerLimit(), seed.upperLimit(), true, now));
            Instant sourceTime = now.minus(seed.ageMinutes(), ChronoUnit.MINUTES);
            pointQuality.save(PointQualitySnapshot.assess(point.code(), point.assetCode(), point.enabled(),
                    point.sampleIntervalSeconds(), point.lowerLimit(), point.upperLimit(), seed.value(), sourceTime,
                    sourceTime.plusSeconds(1), null, now));
        }
    }

    private Component component(String code, String assetCode, String parentCode, String name, String category,
                                String criticality, String position, Instant now) {
        return Component.create(code, assetCode, parentCode, name, category, "设备厂家待确认", "型号待确认",
                "序列号待确认", criticality, position, "2026-01-01", "在役", now);
    }

    private record PointSeed(String code, String assetCode, String componentCode, String name, String metric,
                             String unit, String protocol, String address, int intervalSeconds, Double lowerLimit,
                             Double upperLimit, Double value, int ageMinutes) {
    }
}
