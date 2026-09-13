package com.bproject.ehm.health.application;

import com.bproject.ehm.asset.application.AssetQueryFacade;
import com.bproject.ehm.asset.application.DeviceView;
import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.health.domain.model.HealthAssessment;
import com.bproject.ehm.health.domain.model.HealthFactor;
import com.bproject.ehm.health.domain.model.RulPrediction;
import com.bproject.ehm.health.ports.HealthAssessmentRepository;
import com.bproject.ehm.maintenance.application.WorkOrderApplicationService;
import com.bproject.ehm.maintenance.application.WorkOrderCommand;
import com.bproject.ehm.maintenance.application.WorkOrderView;
import com.bproject.ehm.monitoring.application.DataQualityApplicationService;
import com.bproject.ehm.monitoring.application.DataQualityPointView;
import com.bproject.ehm.monitoring.application.DataQualitySummary;
import com.bproject.ehm.shared.error.DomainConflictException;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import com.bproject.ehm.shared.error.ValidationException;
import com.bproject.ehm.shared.page.PageQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class HealthAssessmentApplicationService {
    private static final String HEALTH_MODEL = "rule-health-baseline-v1.0";
    private static final String FEATURE_VERSION = "threshold-utilization-v1.0";
    private static final String RUL_MODEL = "demo-statistical-rul-v1.0";

    private final HealthAssessmentRepository assessments;
    private final AssetQueryFacade assets;
    private final DataQualityApplicationService dataQuality;
    private final WorkOrderApplicationService workOrders;
    private final Clock clock;

    @Autowired
    public HealthAssessmentApplicationService(HealthAssessmentRepository assessments, AssetQueryFacade assets,
                                              DataQualityApplicationService dataQuality,
                                              WorkOrderApplicationService workOrders) {
        this(assessments, assets, dataQuality, workOrders, Clock.systemUTC());
    }

    HealthAssessmentApplicationService(HealthAssessmentRepository assessments, AssetQueryFacade assets,
                                       DataQualityApplicationService dataQuality,
                                       WorkOrderApplicationService workOrders, Clock clock) {
        this.assessments = assessments;
        this.assets = assets;
        this.dataQuality = dataQuality;
        this.workOrders = workOrders;
        this.clock = clock;
    }

    public HealthAssessment run(String assetCode) {
        String code = Asset.normalizeCode(assetCode);
        DeviceView asset = assets.get(code);
        DataQualitySummary quality = dataQuality.summary(code);
        if (!quality.healthAssessmentAllowed()) {
            throw new DomainConflictException("数据质量未达到自动健康评估门槛：" + quality.assessmentMessage());
        }
        List<DataQualityPointView> points = dataQuality.list(code, new PageQuery(0, 200), null).content();
        List<HealthFactor> factors = points.stream()
                .filter(DataQualityPointView::enabled)
                .filter(point -> "GOOD".equals(point.qualityStatus()))
                .filter(point -> point.lastValue() != null)
                .map(this::factorOf)
                .sorted(Comparator.comparingDouble(HealthFactor::deduction).reversed())
                .toList();
        if (factors.isEmpty()) throw new DomainConflictException("没有可用于健康评估的有效测点");

        double primary = factors.get(0).deduction();
        double secondary = factors.size() > 1 ? factors.get(1).deduction() * 0.4 : 0;
        double tertiary = factors.size() > 2 ? factors.get(2).deduction() * 0.2 : 0;
        int score = (int) Math.round(Math.max(0, 100 - primary - secondary - tertiary));
        double confidence = Math.min(82, 62 + factors.size() * 3 + quality.availabilityPercent() * 0.05);
        Instant now = clock.instant();
        RulPrediction prediction = predictionOf(asset, quality, now);
        List<String> limitations = List.of(
                "当前健康分采用阈值利用率规则基线，尚未替代经甲方验收的设备专用模型",
                "RUL使用Demo统计外推，仅用于验证页面、接口和人工审核流程",
                "设备工况、维修标签和同型设备基线接入后需要重新标定模型"
        );
        HealthAssessment result = HealthAssessment.completed(
                "HA-" + code.replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT),
                code, asset.name(), score, confidence, "规则健康基线 + Demo统计趋势外推",
                HEALTH_MODEL, FEATURE_VERSION, quality.availabilityPercent(), factors, prediction,
                limitations, now.minus(30, ChronoUnit.MINUTES), now, now.plus(30, ChronoUnit.MINUTES));
        return assessments.save(result);
    }

    public HealthAssessment latest(String assetCode) {
        String code = Asset.normalizeCode(assetCode);
        if (!assets.exists(code)) throw new ResourceNotFoundException("未找到设备：" + code);
        return assessments.findLatestByAssetCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("该设备尚无健康评估结果：" + code));
    }

    public List<HealthAssessment> history(String assetCode, int limit) {
        String code = Asset.normalizeCode(assetCode);
        if (!assets.exists(code)) throw new ResourceNotFoundException("未找到设备：" + code);
        return assessments.findRecentByAssetCode(code, limit);
    }

    public HealthAssessment review(String assessmentId, String decision, String comment, String reviewer) {
        HealthAssessment current = find(assessmentId);
        try {
            return assessments.save(current.review(decision, comment, reviewer, clock.instant()));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public HealthAssessment createWorkOrder(String assessmentId, String title, String assignee,
                                            String plannedWindow, String operator) {
        HealthAssessment current = find(assessmentId);
        if (current.workOrderNo() != null && !current.workOrderNo().isBlank()) return current;
        if (current.review() == null || !"ACCEPTED".equals(current.review().decision())) {
            throw new DomainConflictException("只有人工审核为接受的预测建议才能转工单");
        }
        RulPrediction prediction = current.prediction();
        WorkOrderView order = workOrders.create(new WorkOrderCommand(current.assetCode(),
                fallback(title, current.assetName() + "预测风险专项检查"), priorityOf(prediction.riskLevel()),
                fallback(assignee, "设备机修班"), "健康评估 " + current.assessmentId(),
                "人工已接受预测建议。健康分" + current.healthScore() + "；RUL区间"
                        + rangeOf(prediction) + "；建议：" + prediction.recommendation(),
                fallback(plannedWindow, prediction.maintenanceWindow()), fallback(operator, "Demo设备工程师")));
        return assessments.save(current.linkWorkOrder(order.orderNo(), clock.instant()));
    }

    private HealthAssessment find(String assessmentId) {
        return assessments.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("未找到健康评估：" + assessmentId));
    }

    private HealthFactor factorOf(DataQualityPointView point) {
        double utilization = utilization(point);
        double deduction = utilization <= 65 ? 0 : Math.min(25, (utilization - 65) / 35 * 25);
        String reference = point.upperLimit() == null ? "采用可用样本但缺少上限" : "相对配置上限" + point.upperLimit();
        String explanation = utilization >= 90 ? reference + "，已进入高占用区间"
                : utilization >= 75 ? reference + "，高于关注起点"
                : reference + "，当前未形成主要扣分";
        return new HealthFactor(point.pointCode(), point.metric(), point.lastValue(), point.unit(),
                point.upperLimit(), round(utilization), round(deduction), explanation);
    }

    private double utilization(DataQualityPointView point) {
        if (point.upperLimit() == null || point.upperLimit() == 0) return 50;
        if (point.lowerLimit() != null && point.upperLimit() > point.lowerLimit()) {
            return Math.abs((point.lastValue() - point.lowerLimit()) / (point.upperLimit() - point.lowerLimit())) * 100;
        }
        return Math.abs(point.lastValue() / point.upperLimit()) * 100;
    }

    private RulPrediction predictionOf(DeviceView asset, DataQualitySummary quality, Instant now) {
        if (asset.rulDays() == null) return RulPrediction.unavailable("没有经过标定的退化时间基线或寿命标签");
        int expected = Math.max(1, asset.rulDays());
        int margin = Math.max(5, (int) Math.round(expected * 0.25));
        int lower = Math.max(1, expected - margin);
        int upper = expected + margin;
        String risk = expected <= 30 ? "高" : expected <= 90 ? "中" : "低";
        String window = expected <= 30 ? "建议7天内安排检查窗口"
                : expected <= 90 ? "建议30天内纳入计划" : "按现行周期持续监测";
        double confidence = Math.min(75, 50 + quality.availabilityPercent() * 0.2);
        return new RulPrediction("DEMO_ESTIMATE", RUL_MODEL, lower, expected, upper, round(confidence), risk,
                "当前主风险对应部件性能劣化", window,
                "先核验高贡献测点与工况；人工确认后再进入维保计划，不直接下发控制指令",
                "未使用真实故障终点训练；结果不得作为安全联锁或强制停机依据");
    }

    private String priorityOf(String risk) {
        return "高".equals(risk) ? "P1 高" : "中".equals(risk) ? "P2 中" : "P3 低";
    }

    private String rangeOf(RulPrediction prediction) {
        return prediction.expectedDays() == null ? "未生成" : prediction.lowerDays() + "～" + prediction.upperDays() + "天";
    }

    private String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
