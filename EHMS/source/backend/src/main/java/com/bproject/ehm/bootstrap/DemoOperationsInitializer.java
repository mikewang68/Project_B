package com.bproject.ehm.bootstrap;

import com.bproject.ehm.asset.domain.model.CalibrationRecord;
import com.bproject.ehm.asset.domain.model.ConfigurationChange;
import com.bproject.ehm.asset.domain.model.DeviceTemplate;
import com.bproject.ehm.asset.ports.CalibrationRecordRepository;
import com.bproject.ehm.asset.ports.ConfigurationChangeRepository;
import com.bproject.ehm.asset.ports.DeviceTemplateRepository;
import com.bproject.ehm.workbench.domain.model.ShiftHandover;
import com.bproject.ehm.workbench.domain.model.UserTask;
import com.bproject.ehm.workbench.ports.ShiftHandoverRepository;
import com.bproject.ehm.workbench.ports.UserTaskRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Component
@Profile("demo")
@Order(5)
public class DemoOperationsInitializer implements CommandLineRunner {
    private final UserTaskRepository tasks;
    private final ShiftHandoverRepository handovers;
    private final DeviceTemplateRepository templates;
    private final CalibrationRecordRepository calibrations;
    private final ConfigurationChangeRepository changes;

    public DemoOperationsInitializer(UserTaskRepository tasks, ShiftHandoverRepository handovers,
                                     DeviceTemplateRepository templates, CalibrationRecordRepository calibrations,
                                     ConfigurationChangeRepository changes) {
        this.tasks = tasks; this.handovers = handovers; this.templates = templates;
        this.calibrations = calibrations; this.changes = changes;
    }

    @Override public void run(String... args) {
        Instant now = Instant.now();
        if (tasks.count() == 0) {
            tasks.save(UserTask.create("TASK-DEMO-001", "告警处置", "ALARM", "EHM-ALM-0902-001",
                    "确认GT-01起升减速机L3告警", "核验传感器安装、油样与振动频谱",
                    "GT-01", "闫鑫钰", "机修二班", "P1 高", now.plus(2, ChronoUnit.HOURS), now));
            tasks.save(UserTask.create("TASK-DEMO-002", "校准提醒", "CALIBRATION", "CAL-DEMO-001",
                    "QC-02制动响应传感器即将到期", "安排计量校准并上传证书",
                    "QC-02", "计量员", "电气班", "P2 中", now.plus(7, ChronoUnit.DAYS), now));
        }
        if (handovers.count() == 0) {
            handovers.save(ShiftHandover.create("HO-DEMO-001", LocalDate.now(), "白班", "夜班",
                    "白班班长", "夜班班长", "需持续跟踪1条L3告警与2张在途工单。",
                    List.of("GT-01起升减速机L3告警待核验", "GT-03网关离线影响健康评估"),
                    List.of("WO-20260902-004提升链条张紧执行中"),
                    List.of("GT-03遥测断流，恢复后需补传校验"), "交接前请核对挂牌隔离状态。", now));
        }
        if (templates.count() == 0) {
            templates.save(DeviceTemplate.create("TPL-GANTRY-CRANE-V1", "门式起重机标准模板", "门吊",
                    List.of(new DeviceTemplate.TemplateComponent("HOIST", "起升机构", "传动系统", 1, true),
                            new DeviceTemplate.TemplateComponent("BRAKE", "制动器", "安全机构", 2, true)),
                    List.of(new DeviceTemplate.TemplatePoint("VIB-DE", "减速机驱动端振动", "vibration", "mm/s", 25600, 0.0, 11.2, true),
                            new DeviceTemplate.TemplatePoint("TEMP-OIL", "减速机油温", "temperature", "℃", 1, -20.0, 95.0, true)),
                    "月度点检+状态触发", "关键设备", "设备管理员", now));
        }
        if (calibrations.count() == 0) {
            calibrations.save(CalibrationRecord.create("CAL-DEMO-001", "QC-02", "BRAKE", "QC02-BR-TIME",
                    "SEN-QC02-017", "周期校准", 1.8, 1.2, 0.2, "ms", "PASS", "CERT-2026-0818-017",
                    "内部计量室", "计量员张工", now.minus(170, ChronoUnit.DAYS), now.plus(10, ChronoUnit.DAYS),
                    "docs/CERT-2026-0818-017.pdf", "校准后零点漂移恢复至允许范围", now));
            calibrations.save(CalibrationRecord.create("CAL-DEMO-002", "GT-01", "HOIST", "GT01-VIB-DE",
                    "SEN-GT01-006", "异常复核", 7.1, 6.9, 0.3, "mm/s", "LIMITED", "CERT-2026-0901-006",
                    "设备检测中心", "王工", now.minus(22, ChronoUnit.DAYS), now.plus(343, ChronoUnit.DAYS),
                    null, "安装基座需整改，当前测点标记受限", now));
        }
        if (changes.count() == 0) {
            changes.save(ConfigurationChange.create("CFG-DEMO-001", "GT-01", "MEASUREMENT_POINT", "GT01-VIB-DE",
                    "采样策略变更", Map.of("sampleRateHz", 12800, "windowSeconds", 8),
                    Map.of("sampleRateHz", 25600, "windowSeconds", 16),
                    "提高起升减速机故障特征分辨率", "网络增量约0.8Mbps，边缘缓存增量约12GB/月",
                    "设备工程师", "CFG-DEMO-001-before", now));
        }
    }
}
