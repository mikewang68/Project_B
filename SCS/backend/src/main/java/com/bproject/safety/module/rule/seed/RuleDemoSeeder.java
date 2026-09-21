package com.bproject.safety.module.rule.seed;

import com.bproject.safety.module.rule.model.DemoRule;
import com.bproject.safety.module.rule.model.DemoRule.EdgeNode;
import com.bproject.safety.module.rule.model.DemoRule.Param;
import com.bproject.safety.module.rule.model.DemoRule.Version;
import com.bproject.safety.module.rule.model.DemoRule.VersionDiff;
import com.bproject.safety.module.rule.model.RuleStatuses;
import com.bproject.safety.module.rule.repository.RuleRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 规则 Demo 初始化数据：与前端 frontend/src/mock/ruleData.ts 的 22 条种子保持一致，
 * 保证前端移除 Mock 后页面视觉不发生明显变化。仅在存储为空时灌入。
 */
@Component
@Order(11)
public class RuleDemoSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RuleDemoSeeder.class);
    private static final List<String> EDGE_IDS = List.of("EDGE-01", "EDGE-02", "EDGE-03", "EDGE-04");

    private final RuleRepository repository;
    private final com.bproject.safety.support.demo.DemoFeatureGuard guard;

    public RuleDemoSeeder(RuleRepository repository,
                          com.bproject.safety.support.demo.DemoFeatureGuard guard) {
        this.repository = repository;
        this.guard = guard;
    }

    @Override
    public void run(String... args) {
        if (!guard.isSeedEnabled()) {
            return;
        }
        if (repository.count() > 0) {
            return;
        }
        List<DemoRule> seeds = buildSeeds();
        seeds.forEach(repository::save);
        log.info("规则 Demo 数据初始化完成：{} 条", seeds.size());
    }

    /** 构建 22 条种子（可独立用于测试重置）。 */
    public static List<DemoRule> buildSeeds() {
        List<DemoRule> list = new ArrayList<>();

        list.add(seed("RULE-PER-001", "人员进入龙门吊动态禁区", "人员安全", List.of("装卸区 A", "龙门吊作业区"),
                "v3.3", "紧急", RuleStatuses.ACTIVE, "2026-09-04 10:22", "安全员 王建国", "安全总监 赵民",
                "2026-09-04 10:30", true, List.of("人员定位", "电子围栏", "告警中心"),
                "人员定位进入龙门吊动态作业范围时触发紧急告警并联动设备禁动。",
                List.of(Param.of("持续时间阈值", "2 秒"), Param.of("连续采样", "2 次"),
                        Param.danger("告警等级", "紧急"), Param.of("撤离时限", "15 秒"),
                        Param.of("定位精度要求", "≤ 0.5m")),
                List.of("现场声光提醒", "人员手环提醒", "通知安全员", "设备禁动", "PLC 联动"),
                List.of(ver("v3.3", "2026-09-04", "缩短持续时间阈值，撤离时限调整为 15 秒", "王建国", "当前",
                        List.of(diff("持续时间阈值", "3 秒", "2 秒"), diff("撤离时限", "20 秒", "15 秒"))),
                        ver("v3.2", "2026-08-18", "扩大动态禁区半径覆盖范围", "李明", "历史版本",
                                List.of(diff("动态禁区半径", "6m", "8m"))),
                        ver("v3.1", "2026-07-28", "初始规则", "系统管理员", "历史版本", null))));

        list.add(seed("RULE-PER-002", "电子围栏越界判定", "人员安全", List.of("装卸区 A", "装卸区 B", "箱区 B"),
                "v2.1", "严重", RuleStatuses.ACTIVE, "2026-08-26 15:10", "安全员 李娜", "安全总监 赵民",
                "2026-08-26 16:00", false, List.of("电子围栏", "人员定位", "告警中心"),
                "人员越过电子围栏边界并持续停留时生成越界告警。",
                List.of(Param.of("越界判定距离", "0.8m"), Param.of("持续时间阈值", "3 秒"),
                        Param.of("连续采样", "3 次"), Param.of("告警等级", "严重")),
                List.of("现场声光提醒", "人员手环提醒", "通知安全员"),
                List.of(ver("v2.1", "2026-08-26", "优化边界抖动过滤", "李娜", "当前", null),
                        ver("v2.0", "2026-07-30", "初始规则", "系统管理员", "历史版本", null))));

        list.add(seed("RULE-PER-003", "危险区域人员滞留提醒", "人员安全", List.of("维修通道", "翻箱机区"),
                "v1.6", "预警", RuleStatuses.REVIEW, "2026-09-03 17:40", "安全员 李娜", "待审批",
                "—", false, List.of("人员定位", "告警中心"),
                "人员在危险区域滞留超过时限时逐级提醒。",
                List.of(Param.of("滞留时限", "90 秒"), Param.of("升级时限", "180 秒"),
                        Param.of("告警等级", "预警")),
                List.of("人员手环提醒", "通知安全员"),
                List.of(ver("v1.6", "2026-09-03", "滞留时限由 60 秒调整为 90 秒", "李娜", "当前", null))));

        list.add(seed("RULE-PER-004", "人员定位信号丢失处置", "人员安全", List.of("全部区域"),
                "v1.2", "预警", RuleStatuses.DRAFT, "2026-09-02 11:05", "安全员 王建国", "—",
                "—", false, List.of("人员定位"),
                "手环定位信号丢失超过时限后提示现场核查。",
                List.of(Param.of("信号丢失时限", "45 秒"), Param.of("告警等级", "预警")),
                List.of("通知安全员"),
                List.of(ver("v1.2", "2026-09-02", "草稿：补充低电量联动", "王建国", "当前", null))));

        list.add(seed("RULE-DEV-003", "转运车辆距离预警", "设备安全", List.of("翻箱机区", "装卸区 A"),
                "v2.4", "严重", RuleStatuses.ACTIVE, "2026-09-04 09:18", "设备管理员 周海", "安全总监 赵民",
                "2026-09-04 09:30", true, List.of("设备防碰撞", "告警中心"),
                "按设备间距与相对速度分级预警，紧急距离内联动减速 / 停机。",
                List.of(Param.of("预警距离", "10m"), Param.of("严重距离", "6m"),
                        Param.danger("紧急距离", "3m"), Param.of("相对速度修正", "开启"),
                        Param.of("制动距离修正", "开启"), Param.of("天气修正", "开启")),
                List.of("通知司机", "通知安全员", "减速请求", "设备停机", "PLC 联动"),
                List.of(ver("v2.4", "2026-09-04", "雨天距离阈值修正系数调整", "周海", "当前",
                        List.of(diff("预警距离", "8m", "10m"), diff("紧急距离", "2.5m", "3m"))),
                        ver("v2.3", "2026-08-12", "加入相对速度修正", "周海", "历史版本", null),
                        ver("v2.2", "2026-07-20", "初始规则", "系统管理员", "历史版本", null))));

        list.add(seed("RULE-DEV-009", "车辆通道距离预警", "设备安全", List.of("车辆通道", "翻箱机区"),
                "v1.5", "严重", RuleStatuses.ACTIVE, "2026-08-20 14:32", "设备管理员 周海", "安全总监 赵民",
                "2026-08-20 15:00", false, List.of("设备防碰撞", "告警中心"),
                "车辆通道会车 / 跟车距离分级预警。",
                List.of(Param.of("预警距离", "8m"), Param.of("严重距离", "5m"),
                        Param.danger("紧急距离", "2.5m"), Param.of("相对速度修正", "开启")),
                List.of("通知司机", "通知调度员", "减速请求"),
                List.of(ver("v1.5", "2026-08-20", "通道限速联动调整", "周海", "当前", null),
                        ver("v1.4", "2026-07-15", "初始规则", "系统管理员", "历史版本", null))));

        DemoRule dev002 = seed("RULE-DEV-002", "龙门吊旋转半径入侵", "设备安全", List.of("龙门吊作业区"),
                "v2.0", "紧急", RuleStatuses.MISMATCH, "2026-09-01 08:50", "设备管理员 周海", "安全总监 赵民",
                "2026-09-01 09:10", true, List.of("设备防碰撞", "人员定位", "告警中心"),
                "人员 / 车辆进入吊臂旋转半径时触发紧急联动。",
                List.of(Param.of("旋转半径", "12m"), Param.danger("告警等级", "紧急"),
                        Param.of("制动距离修正", "开启")),
                List.of("现场声光提醒", "通知司机", "设备禁动", "PLC 联动"),
                List.of(ver("v2.0", "2026-09-01", "旋转半径扩大至 12m", "周海", "当前", null),
                        ver("v1.9", "2026-08-02", "初始规则", "系统管理员", "历史版本", null)));
        dev002.edgeNodes = List.of(
                new EdgeNode("EDGE-01", "v2.0", RuleStatuses.EDGE_SYNCED),
                new EdgeNode("EDGE-02", "v2.0", RuleStatuses.EDGE_SYNCED),
                new EdgeNode("EDGE-03", "v1.9", RuleStatuses.EDGE_MISMATCH),
                new EdgeNode("EDGE-04", "v2.0", RuleStatuses.EDGE_SYNCED));
        list.add(dev002);

        list.add(seed("RULE-DEV-010", "设备超速联动减速", "设备安全", List.of("车辆通道", "装卸区 B"),
                "v1.3", "严重", RuleStatuses.APPROVED, "2026-09-03 16:20", "设备管理员 周海", "安全总监 赵民",
                "待发布", false, List.of("设备防碰撞"),
                "车辆超过区域限速时自动请求减速。",
                List.of(Param.of("区域限速", "15 km/h"), Param.of("超速比例", "10%"),
                        Param.of("告警等级", "严重")),
                List.of("通知司机", "减速请求", "通知调度员"),
                List.of(ver("v1.3", "2026-09-03", "评审通过，待发布", "周海", "当前", null))));

        list.add(seed("RULE-DEV-011", "雨天制动距离修正", "设备安全", List.of("全部区域"),
                "v0.8", "预警", RuleStatuses.DRAFT, "2026-09-02 10:02", "设备管理员 周海", "—",
                "—", false, List.of("设备防碰撞"),
                "草稿：小雨 / 雾天下距离阈值按系数放大。",
                List.of(Param.of("小雨修正系数", "1.15"), Param.of("雾天修正系数", "1.3")),
                List.of("减速请求"),
                List.of(ver("v0.8", "2026-09-02", "草稿：待现场数据验证", "周海", "当前", null))));

        list.add(seed("RULE-AI-002", "未佩戴安全帽识别", "AI识别", List.of("装卸区 A", "装卸区 B"),
                "v1.8", "一般", RuleStatuses.ACTIVE, "2026-08-28 13:44", "安全员 李娜", "安全总监 赵民",
                "2026-08-28 14:00", false, List.of("AI违规识别", "告警中心"),
                "PPE 模型识别未佩戴安全帽，低置信度进入人工复核。",
                List.of(Param.of("置信度阈值", "85%"), Param.of("持续时间", "2 秒"),
                        Param.of("连续帧", "5 帧"), Param.of("低置信度处理", "进入人工复核"),
                        Param.of("模型", "PPE-Detection-v2.4.1")),
                List.of("进入人工复核", "通知安全员"),
                List.of(ver("v1.8", "2026-08-28", "阈值由 80% 提升至 85%", "李娜", "当前", null),
                        ver("v1.7", "2026-08-05", "初始规则", "系统管理员", "历史版本", null))));

        list.add(seed("RULE-AI-003", "翻越护栏识别", "AI识别", List.of("装卸区 A", "箱区 B"),
                "v1.4", "严重", RuleStatuses.ACTIVE, "2026-08-22 09:30", "安全员 李娜", "安全总监 赵民",
                "2026-08-22 10:00", false, List.of("AI违规识别", "告警中心"),
                "行为识别模型检测翻越护栏动作并生成违规事件。",
                List.of(Param.of("置信度阈值", "88%"), Param.of("连续帧", "6 帧"),
                        Param.of("告警等级", "严重"), Param.of("模型", "Behavior-v1.9")),
                List.of("进入人工复核", "现场声光提醒", "通知安全员"),
                List.of(ver("v1.4", "2026-08-22", "降低夜间误报", "李娜", "当前", null))));

        list.add(seed("RULE-AI-005", "低置信度事件复核策略", "AI识别", List.of("全部区域"),
                "v0.9", "一般", RuleStatuses.DRAFT, "2026-09-01 15:26", "安全员 李娜", "—",
                "—", false, List.of("AI违规识别"),
                "草稿：60%~85% 置信度统一进入人工复核队列，不自动升级。",
                List.of(Param.of("复核区间下限", "60%"), Param.of("复核区间上限", "85%")),
                List.of("进入人工复核"),
                List.of(ver("v0.9", "2026-09-01", "草稿：复核 SLA 待定", "李娜", "当前", null))));

        list.add(seed("RULE-AI-006", "摄像头遮挡 / 清晰度下降告警", "AI识别", List.of("全部区域"),
                "v1.1", "预警", RuleStatuses.REVIEW, "2026-09-03 18:02", "安全员 李娜", "待审批",
                "—", false, List.of("AI违规识别", "运维监控"),
                "摄像头画面质量下降时标记 AI 能力受限，不输出\"无违规\"结论。",
                List.of(Param.of("清晰度阈值", "0.55"), Param.of("遮挡判定时长", "10 秒")),
                List.of("通知安全员", "通知调度员"),
                List.of(ver("v1.1", "2026-09-03", "提交评审", "李娜", "当前", null))));

        list.add(seed("RULE-ALM-001", "严重事件分级升级策略", "告警策略", List.of("全部区域"),
                "v2.2", "严重", RuleStatuses.ACTIVE, "2026-09-03 21:15", "调度员 陈晓", "安全总监 赵民",
                "2026-09-04 08:30", false, List.of("告警中心"),
                "严重 / 紧急事件按确认与处置时限自动升级。",
                List.of(Param.of("严重确认时限", "3 分钟"), Param.of("紧急确认时限", "1 分钟"),
                        Param.of("升级层级", "2 级")),
                List.of("通知安全员", "通知调度员"),
                List.of(ver("v2.2", "2026-09-04", "紧急确认时限收紧", "陈晓", "当前", null))));

        list.add(seed("RULE-ALM-002", "SLA 超时升级", "告警策略", List.of("全部区域"),
                "v1.7", "预警", RuleStatuses.REVIEW, "2026-09-03 19:12", "调度员 陈晓", "待审批",
                "—", false, List.of("告警中心", "统计分析"),
                "处置 SLA 即将超时 / 已超时自动提醒并升级。",
                List.of(Param.of("预警提前量", "5 分钟"), Param.of("超时升级", "开启")),
                List.of("通知调度员", "通知安全员"),
                List.of(ver("v1.7", "2026-09-03", "提交评审", "陈晓", "当前", null))));

        list.add(seed("RULE-ALM-003", "重复告警聚合", "告警策略", List.of("全部区域"),
                "v1.1", "一般", RuleStatuses.DISABLED, "2026-07-30 17:48", "调度员 陈晓", "安全总监 赵民",
                "2026-07-18 09:00", false, List.of("告警中心"),
                "同对象同类型 5 分钟内重复告警聚合展示（评估中，暂时停用）。",
                List.of(Param.of("聚合窗口", "5 分钟")),
                List.of(),
                List.of(ver("v1.1", "2026-07-30", "停用：聚合导致漏报风险，待重新评估", "陈晓", "当前", null))));

        list.add(seed("RULE-ALM-004", "误报自动收敛建议", "告警策略", List.of("全部区域"),
                "v0.4", "一般", RuleStatuses.DRAFT, "2026-09-02 16:40", "调度员 陈晓", "—",
                "—", false, List.of("告警中心", "AI违规识别"),
                "草稿：同一规则高频误报时给出收敛建议，不自动关闭告警。",
                List.of(Param.of("误报样本阈值", "20 次 / 周")),
                List.of("进入人工复核"),
                List.of(ver("v0.4", "2026-09-02", "草稿", "陈晓", "当前", null))));

        list.add(seed("RULE-LNK-001", "紧急事件设备联动编排", "联动策略",
                List.of("装卸区 A", "龙门吊作业区", "翻箱机区"),
                "v2.5", "紧急", RuleStatuses.ACTIVE, "2026-09-04 11:06", "设备管理员 周海", "安全总监 赵民",
                "2026-09-04 11:20", true, List.of("告警中心", "设备防碰撞", "人员定位"),
                "紧急告警按声光 → 手环 → 通知 → 减速 → 停机 → PLC 回执顺序联动。",
                List.of(Param.danger("联动触发等级", "紧急"), Param.danger("设备停机", "自动执行"),
                        Param.danger("PLC 联动", "开启"), Param.of("PLC 回执超时", "8 秒")),
                List.of("现场声光提醒", "人员手环提醒", "通知安全员", "通知调度员", "减速请求",
                        "设备停机", "PLC 联动"),
                List.of(ver("v2.5", "2026-09-04", "PLC 回执超时由 10 秒收紧至 8 秒", "周海", "当前", null),
                        ver("v2.4", "2026-08-14", "初始规则", "系统管理员", "历史版本", null))));

        list.add(seed("RULE-LNK-002", "现场声光提醒策略", "联动策略", List.of("全部区域"),
                "v1.4", "预警", RuleStatuses.ACTIVE, "2026-08-19 13:12", "安全员 王建国", "安全总监 赵民",
                "2026-08-19 13:30", false, List.of("告警中心"),
                "预警及以上事件触发现场声光提醒。",
                List.of(Param.of("触发等级", "预警"), Param.of("提醒持续", "20 秒")),
                List.of("现场声光提醒"),
                List.of(ver("v1.4", "2026-08-19", "音量曲线调整", "王建国", "当前", null))));

        list.add(seed("RULE-LNK-003", "夜间联动加强策略", "联动策略", List.of("全部区域"),
                "v0.6", "严重", RuleStatuses.DRAFT, "2026-09-01 20:31", "安全员 王建国", "—",
                "—", false, List.of("告警中心"),
                "草稿：22:00-06:00 夜间作业自动下调一级触发阈值。",
                List.of(Param.of("夜间时段", "22:00 - 06:00"), Param.of("阈值下调", "15%")),
                List.of("现场声光提醒", "通知安全员"),
                List.of(ver("v0.6", "2026-09-01", "草稿", "王建国", "当前", null))));

        list.add(seed("RULE-NTF-001", "安全员通知矩阵", "通知策略", List.of("全部区域"),
                "v2.0", "一般", RuleStatuses.ACTIVE, "2026-08-15 10:40", "调度员 陈晓", "安全总监 赵民",
                "2026-08-15 11:00", false, List.of("告警中心"),
                "按风险等级与区域匹配值班安全员通知通道。",
                List.of(Param.of("通知通道", "手环 + 终端"), Param.of("送达确认", "开启")),
                List.of("通知安全员"),
                List.of(ver("v2.0", "2026-08-15", "值班表轮换同步", "陈晓", "当前", null))));

        list.add(seed("RULE-NTF-002", "调度值班升级通知", "通知策略", List.of("全部区域"),
                "v1.2", "预警", RuleStatuses.REVIEW, "2026-09-03 21:08", "调度员 陈晓", "待审批",
                "—", false, List.of("告警中心"),
                "事件升级后逐级通知值班调度与值班主任。",
                List.of(Param.of("逐级间隔", "90 秒")),
                List.of("通知调度员"),
                List.of(ver("v1.2", "2026-09-03", "提交评审", "陈晓", "当前", null))));

        list.add(seed("RULE-NTF-003", "班组安全日报推送", "通知策略", List.of("全部区域"),
                "v0.5", "一般", RuleStatuses.DRAFT, "2026-09-02 09:47", "调度员 陈晓", "—",
                "—", false, List.of("统计分析"),
                "草稿：每日 08:00 向班组推送前一日安全简报。",
                List.of(Param.of("推送时间", "每日 08:00")),
                List.of(),
                List.of(ver("v0.5", "2026-09-02", "草稿", "陈晓", "当前", null))));

        return list;
    }

    // ---------- 装配辅助 ----------

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static DemoRule seed(String id, String name, String category, List<String> areas, String version,
                                 String risk, String status, String updatedAt, String owner, String approver,
                                 String effectiveAt, boolean highRisk, List<String> related, String description,
                                 List<Param> params, List<String> actions, List<Version> versions) {
        DemoRule r = new DemoRule();
        r.id = id;
        r.name = name;
        r.category = category;
        r.areas = new ArrayList<>(areas);
        r.version = version;
        r.platformVersion = version;
        // 种子中文风险仅用于装配，落模型统一转机器 code；status 已为 RuleStatuses code。
        r.riskCode = com.bproject.safety.module.alert.model.RiskLevels.normalize(risk);
        r.statusCode = status;
        r.updatedAt = updatedAt;
        r.owner = owner;
        r.approver = approver;
        r.effectiveAt = effectiveAt;
        r.highRisk = highRisk;
        r.relatedModules = new ArrayList<>(related);
        r.description = description;
        r.params = new ArrayList<>(params);
        r.actions = new ArrayList<>(actions);
        r.versions = new ArrayList<>(versions);
        r.edgeNodes = syncedNodes(version);
        return r;
    }

    private static List<EdgeNode> syncedNodes(String version) {
        return EDGE_IDS.stream().map(n -> new EdgeNode(n, version, RuleStatuses.EDGE_SYNCED)).toList();
    }

    private static Version ver(String version, String date, String note, String author, String state,
                               List<VersionDiff> diffs) {
        return new Version(version, date, note, author, state, diffs);
    }

    private static VersionDiff diff(String label, String from, String to) {
        return new VersionDiff(label, from, to);
    }
}
