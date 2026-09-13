package com.bproject.ehm.bootstrap;

import com.bproject.ehm.reliability.application.ReliabilityGovernanceApplicationService;
import com.bproject.ehm.reliability.domain.model.AlarmRule;
import com.bproject.ehm.reliability.domain.model.KnowledgeCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("demo")
public class DemoReliabilityInitializer implements ApplicationRunner {
    private final ReliabilityGovernanceApplicationService governance;

    public DemoReliabilityInitializer(ReliabilityGovernanceApplicationService governance) {
        this.governance = governance;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedFailureModes();
        seedAlarmRules();
        seedKnowledgeCases();
        seedSlaPolicies();
    }

    private void seedFailureModes() {
        if (!governance.failureModes().isEmpty()) return;
        governance.createFailureMode("GT-GEAR-BRG-001", "门式起重机", "起升减速机高速轴轴承",
                "轴承磨损或润滑劣化", "振动升高、温升，严重时造成减速机停机",
                "润滑油污染、装配偏差、长期重载或轴承疲劳", 8, 5, 4,
                "振动RMS与包络趋势监测、油样和周期点检",
                "增加轴向测点；规定工况复测；油样异常时安排拆检", "设备工程师");
        governance.createFailureMode("QC-BRAKE-PAD-002", "桥式起重机", "起升制动器",
                "摩擦片磨损或间隙异常", "制动响应变慢，存在溜钩和停车距离增加风险",
                "高频制动、调整不当、摩擦片磨损或弹簧疲劳", 9, 4, 3,
                "制动响应时间、间隙测量和人工外观检查",
                "设置响应时间变化率规则；超限时停机隔离后复测间隙", "机修一班");
        governance.createFailureMode("DF-FILTER-DP-001", "除尘设备", "滤袋与压差回路",
                "滤袋堵塞或压差测量失真", "风量下降、能耗升高、粉尘排放控制能力下降",
                "粉尘负荷上升、清灰失效、滤袋结露或压差传感器漂移", 6, 7, 2,
                "压差、风量、电流组合规则和人工巡检",
                "先校验传感器，再检查清灰机构和滤袋状态", "环保班");
    }

    private void seedAlarmRules() {
        if (!governance.alarmRules().isEmpty()) return;
        AlarmRule vibration = governance.createAlarmRule("RULE-GT-VIB-RMS", "起升减速机振动持续超限",
                "门式起重机", "振动速度RMS", "value > 7.1 mm/s AND load >= 70%",
                "value < 6.0 mm/s 持续10分钟", "L3", 180, "设备工程师");
        governance.publishRule(vibration.ruleCode(), "Demo设备主管");
        governance.createAlarmRule("RULE-GT-TEMP-RATE", "轴承温升速率异常", "门式起重机",
                "轴承温度变化率", "delta_10m > 8 ℃ AND value > 65 ℃",
                "delta_10m < 3 ℃ 持续20分钟", "L2", 600, "设备工程师");
        AlarmRule loss = governance.createAlarmRule("RULE-DQ-DATA-LOSS", "关键测点数据断流",
                "通用", "数据新鲜度", "now - source_time > 3 * sample_interval",
                "连续收到5个有效样本", "DATA", 60, "数据管理员");
        governance.publishRule(loss.ruleCode(), "Demo数据主管");
    }

    private void seedKnowledgeCases() {
        if (!governance.knowledgeCases().isEmpty()) return;
        KnowledgeCase brake = governance.createKnowledgeCase("QC-BRAKE-PAD-002", "桥式起重机", "起升制动器",
                "QC-02制动响应变慢处置案例", "相同工况下制动响应P95由1.08秒升至1.42秒",
                "摩擦片磨损并伴随制动间隙偏大",
                List.of("核对载荷与操作工况", "检查制动间隙和摩擦片厚度", "完成调整后按相同工况复测"),
                "调整制动间隙并更换超限摩擦片", "相同工况制动响应≤1.20秒且连续3次稳定",
                "WO-20260907-83A31EE0");
        governance.verifyCase(brake.caseNo(), "Demo设备主管");
        governance.createKnowledgeCase("GT-GEAR-BRG-001", "门式起重机", "起升减速机高速轴轴承",
                "振动包络趋势升高排查模板", "重载起升时包络能量持续高于工况基线",
                "候选原因为润滑劣化或轴承早期损伤，需油样和频谱确认",
                List.of("校验传感器安装和校准", "对齐载荷与转速工况", "采集油样和轴向振动", "规定工况复测"),
                "依据油样、频谱和现场检查决定补油、换油或拆检",
                "复测振动回到基线控制带且油样指标符合设备标准", "WO-20260908-D9CC6838");
    }

    private void seedSlaPolicies() {
        if (!governance.slaPolicies().isEmpty()) return;
        governance.saveSla("L4", 5, 10, 30, "项目值班负责人");
        governance.saveSla("L3", 10, 20, 60, "设备主管");
        governance.saveSla("L2", 30, 60, 240, "专业工程师");
        governance.saveSla("L1", 120, 240, 1440, "责任班组长");
        governance.saveSla("DATA", 15, 30, 120, "数据平台主管");
    }
}
