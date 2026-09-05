package com.bproject.ehm.service;

import com.bproject.ehm.domain.Alarm;
import com.bproject.ehm.domain.Device;
import com.bproject.ehm.domain.WorkOrder;
import com.bproject.ehm.repository.AlarmRepository;
import com.bproject.ehm.repository.DeviceRepository;
import com.bproject.ehm.repository.WorkOrderRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class DemoDataInitializer implements CommandLineRunner {
    private final DeviceRepository devices;
    private final AlarmRepository alarms;
    private final WorkOrderRepository workOrders;

    public DemoDataInitializer(DeviceRepository devices, AlarmRepository alarms, WorkOrderRepository workOrders) {
        this.devices = devices;
        this.alarms = alarms;
        this.workOrders = workOrders;
    }

    @Override
    public void run(String... args) {
        seedDevices();
        seedAlarms();
        seedWorkOrders();
    }

    private void seedDevices() {
        if (devices.count() > 0) return;
        Instant now = Instant.now();
        devices.saveAll(List.of(
                new Device("GT-01", "1#门式起重机", "门吊", "装卸一区", "重载作业", 58,
                        "L3 严重", "severe", 98.6, "已接入", "起升减速机包络趋势异常",
                        "2026-09-06", "机修二班", 68.2, 6.8, 162.4, 21, now),
                new Device("QC-02", "2#桥式起重机", "桥吊", "装卸二区", "待机", 84,
                        "L1 提示", "info", 99.1, "已接入", "制动响应时间轻微抬升",
                        "2026-09-18", "机修一班", 46.1, 3.2, 18.6, 96, now),
                new Device("FX-01", "1#翻箱机", "翻箱机", "翻卸作业区", "运行", 76,
                        "L2 警告", "warn", 96.2, "可接入", "液压执行时间连续3班次上升",
                        "2026-09-10", "液压班", 59.4, 4.7, 118.3, 54, now),
                new Device("TS-03", "3#提升机", "提升机", "转运站", "检修", 69,
                        "检修中", "maintenance", 92.8, "需新增传感器", "链条张紧度依赖人工点检",
                        "2026-09-02", "机修三班", 38.0, 5.1, 0.0, null, now),
                new Device("UL-02", "2#卸料线", "卸料设备", "卸料二区", "运行", 91,
                        "正常", "good", 98.9, "已接入", "无活动告警",
                        "2026-10-03", "运输班", 42.7, 2.4, 86.2, 180, now),
                new Device("DF-01", "1#除尘系统", "除尘设备", "转运站", "运行", 73,
                        "L2 警告", "warn", 95.4, "可接入", "滤袋压差偏高，原因待确认",
                        "2026-09-08", "环保班", 37.6, 2.8, 71.9, 45, now),
                new Device("GT-03", "3#门式起重机", "门吊", "装卸三区", "离线", null,
                        "数据中断", "limited", 31.6, "已接入", "边缘网关离线 24 min",
                        "2026-09-25", "机修二班", null, null, null, null, now),
                new Device("QC-04", "4#桥式起重机", "桥吊", "检修库", "停机", null,
                        "仅人工点检", "offline", null, "仅人工点检", "暂不具备在线评估条件",
                        "2026-09-04", "机修一班", null, null, null, null, now)
        ));
    }

    private void seedAlarms() {
        if (alarms.count() > 0) return;
        Instant now = Instant.now();
        alarms.saveAll(List.of(
                new Alarm("EHM-ALM-0902-001", "GT-01", "1#门式起重机", "起升减速机", "L3 严重",
                        "severe", "包络能量连续30 min高于工况基线38%", "待确认", "已超 12 min", "规则+趋势",
                        now.minus(42, ChronoUnit.MINUTES), null, null, "机修二班"),
                new Alarm("EHM-ALM-0902-007", "FX-01", "1#翻箱机", "液压执行机构", "L2 警告",
                        "warn", "执行时间较基线上升18%", "处理中", "剩余 42 min", "统计基线",
                        now.minus(18, ChronoUnit.MINUTES), now.minus(12, ChronoUnit.MINUTES), null, "液压班"),
                new Alarm("EHM-ALM-0902-009", "DF-01", "1#除尘系统", "除尘滤袋", "L2 警告",
                        "warn", "压差高且风量下降，原因待确认", "新建", "剩余 55 min", "组合规则",
                        now.minus(5, ChronoUnit.MINUTES), null, null, "环保班"),
                new Alarm("EHM-DQ-0902-003", "GT-03", "3#门式起重机", "边缘网关", "数据中断",
                        "limited", "遥测断流24 min，健康评估已暂停", "处理中", "剩余 16 min", "数据质量",
                        now.minus(24, ChronoUnit.MINUTES), now.minus(20, ChronoUnit.MINUTES), null, "系统运维组"),
                new Alarm("EHM-ALM-0901-042", "QC-02", "2#桥式起重机", "制动器", "L1 提示",
                        "info", "制动响应时间P95轻微上升", "已确认", "未超期", "变化率",
                        now.minus(9, ChronoUnit.HOURS), now.minus(8, ChronoUnit.HOURS), null, "机修一班"),
                new Alarm("EHM-ALM-0901-031", "TS-03", "3#提升机", "提升链条", "L2 警告",
                        "warn", "人工点检发现张紧度偏低", "待验证", "剩余 3 h", "人工点检",
                        now.minus(13, ChronoUnit.HOURS), now.minus(12, ChronoUnit.HOURS), null, "机修三班")
        ));
    }

    private void seedWorkOrders() {
        if (workOrders.count() > 0) return;
        Instant now = Instant.now();
        workOrders.saveAll(List.of(
                new WorkOrder("WO-20260902-018", "GT-01", "1#门式起重机", "起升减速机振动异常专项检查",
                        "P1 高", "待审批", "机修二班", "L3告警", "核验传感器、采集油样并复测振动频谱",
                        "2026-09-05 夜班", now.minus(30, ChronoUnit.MINUTES), now.minus(30, ChronoUnit.MINUTES)),
                new WorkOrder("WO-20260902-022", "DF-01", "1#除尘系统", "滤袋压差异常检查",
                        "P2 中", "待审批", "环保班", "状态告警", "排查滤袋堵塞与压差传感器",
                        "2026-09-03 白班", now.minus(20, ChronoUnit.MINUTES), now.minus(20, ChronoUnit.MINUTES)),
                new WorkOrder("WO-20260901-011", "QC-02", "2#桥式起重机", "制动响应复测",
                        "P2 中", "待执行", "机修一班", "点检发现", "检查制动间隙、磨损和动作时间",
                        "2026-09-03 02:00", now.minus(10, ChronoUnit.HOURS), now.minus(2, ChronoUnit.HOURS)),
                new WorkOrder("WO-20260902-004", "TS-03", "3#提升机", "提升链条张紧",
                        "P1 高", "执行中", "机修三班", "人工点检", "完成隔离挂牌、调整张紧并复测",
                        "当前窗口", now.minus(4, ChronoUnit.HOURS), now.minus(25, ChronoUnit.MINUTES)),
                new WorkOrder("WO-20260831-039", "UL-02", "2#卸料线", "托辊更换后负载复测",
                        "P2 中", "待复测", "设备工程师", "定期维护", "复测温度、振动和跑偏状态",
                        "2026-09-03 白班", now.minus(2, ChronoUnit.DAYS), now.minus(3, ChronoUnit.HOURS))
        ));
    }
}
