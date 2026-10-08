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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class HealthAssessmentApplicationService {
    private static final String HEALTH_MODEL = "rule-health-baseline-v1.0";
    private static final String FEATURE_VERSION = "threshold-utilization-v1.0";

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
        return assess(assetCode, false);
    }

    public HealthAssessment replay(String assetCode) {
        return assess(assetCode, true);
    }

    private HealthAssessment assess(String assetCode, boolean replay) {
        String code = Asset.normalizeCode(assetCode);
        DeviceView asset = assets.get(code);
        List<DataQualityPointView> points = replay ? dataQuality.historicalPoints(code) : dataQuality.allPoints(code);
        DataQualitySummary quality = dataQuality.summarize(code, points);
        if (!quality.healthAssessmentAllowed()) {
            throw new DomainConflictException("数据质量未达到自动健康评估门槛：" + quality.assessmentMessage());
        }
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
        RulPrediction prediction = RulPrediction.unavailable("尚未接入经验证的寿命模型、故障终点和工况标签；台账RUL字段不是模型预测结果");
        Instant inputStart = points.stream().filter(p -> "GOOD".equals(p.qualityStatus()))
                .map(DataQualityPointView::sourceTimestamp).min(Instant::compareTo).orElse(now);
        Instant inputEnd = points.stream().filter(p -> "GOOD".equals(p.qualityStatus()))
                .map(DataQualityPointView::sourceTimestamp).max(Instant::compareTo).orElse(now);
        List<String> limitations = List.of(
                "当前健康分采用阈值利用率规则基线，尚未替代经甲方验收的设备专用模型",
                "可信度是规则覆盖度提示，不是经校准的统计置信概率；RUL未启用",
                replay ? "历史回放：仅核验数据库中已有样本的规则结果，不能代表设备当前健康状态"
                        : "仅使用当前有效测点快照，不等同于连续采集窗口或经训练AI预测"
        );
        HealthAssessment result = new HealthAssessment(
                "HA-" + code.replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT),
                code, asset.name(), score, score >= 85 ? "健康" : score >= 70 ? "关注" : score >= 50 ? "异常" : "严重",
                round(confidence), "COMPLETED", replay ? "历史回放 · 阈值利用率规则评估" : "阈值利用率规则评估（非AI寿命模型）",
                HEALTH_MODEL, FEATURE_VERSION, quality.availabilityPercent(), factors, prediction,
                limitations, inputStart, inputEnd, now, replay ? now : inputEnd.plusSeconds(
                        points.stream().filter(DataQualityPointView::enabled).mapToLong(p -> Math.max(30, p.sampleIntervalSeconds() * 5L)).min().orElse(30)),
                null, null, null);
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
                fallback(title, current.assetName() + "规则评估专项核查"), priorityOf(current.healthScore()<70?"高":current.healthScore()<85?"中":"低"),
                fallback(assignee, "设备机修班"), "健康评估 " + current.assessmentId(),
                "人工已接受核查建议。评估方式：" + current.method() + "；输入截止：" + current.inputWindowEnd()
                        + "；规则健康分" + current.healthScore() + "；RUL区间" + rangeOf(prediction)
                        + "。先核验现场状态与高贡献测点；不作为自动停机依据。",
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
