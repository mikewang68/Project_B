package com.bproject.ehm.monitoring.application;

import com.bproject.ehm.asset.application.AssetQueryFacade;
import com.bproject.ehm.asset.application.MeasurementPointQueryFacade;
import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.asset.domain.model.MeasurementPoint;
import com.bproject.ehm.monitoring.domain.model.PointQualitySnapshot;
import com.bproject.ehm.monitoring.domain.model.QualityStatus;
import com.bproject.ehm.monitoring.ports.PointQualityRepository;
import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DataQualityApplicationService {
    private final MeasurementPointQueryFacade points;
    private final PointQualityRepository quality;
    private final AssetQueryFacade assets;
    private final TelemetrySeriesPort telemetry;
    private final Clock clock;

    @Autowired
    public DataQualityApplicationService(MeasurementPointQueryFacade points, PointQualityRepository quality,
                                         AssetQueryFacade assets, TelemetrySeriesPort telemetry) {
        this(points, quality, assets, telemetry, Clock.systemUTC());
    }

    DataQualityApplicationService(MeasurementPointQueryFacade points, PointQualityRepository quality,
                                  AssetQueryFacade assets, TelemetrySeriesPort telemetry, Clock clock) {
        this.points = points;
        this.quality = quality;
        this.assets = assets;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    public PageResult<DataQualityPointView> list(String assetCode, PageQuery page, String keyword) {
        String normalizedAsset = requireAsset(assetCode);
        PageResult<MeasurementPoint> pointPage = points.page(normalizedAsset, page, keyword);
        Map<String, PointQualitySnapshot> snapshots = quality.findByPointCodes(
                        pointPage.content().stream().map(MeasurementPoint::code).toList()).stream()
                .collect(Collectors.toMap(PointQualitySnapshot::pointCode, Function.identity()));
        Instant now = clock.instant();
        return pointPage.map(point -> DataQualityPointView.compose(point,
                snapshots.getOrDefault(point.code(), PointQualitySnapshot.missing(
                        point.code(), point.assetCode(), point.enabled(), now))));
    }

    public DataQualitySummary summary(String assetCode) {
        String normalizedAsset = requireAsset(assetCode);
        List<String> activeCodes = points.activeCodes(normalizedAsset);
        List<String> enabledCodes = points.enabledCodes(normalizedAsset);
        long total = activeCodes.size();
        long enabled = enabledCodes.size();
        Map<QualityStatus, Long> counts = quality.countByStatus(normalizedAsset, activeCodes);
        long good = count(counts, QualityStatus.GOOD);
        long disabled = count(counts, QualityStatus.DISABLED) + Math.max(0, total - enabled - count(counts, QualityStatus.DISABLED));
        long observedEnabled = counts.entrySet().stream()
                .filter(entry -> entry.getKey() != QualityStatus.DISABLED)
                .mapToLong(Map.Entry::getValue).sum();
        long missing = count(counts, QualityStatus.MISSING) + Math.max(0, enabled - observedEnabled);
        long delayed = count(counts, QualityStatus.DELAYED);
        long outOfRange = count(counts, QualityStatus.OUT_OF_RANGE);
        long invalid = count(counts, QualityStatus.INVALID);
        double availability = enabled == 0 ? 0 : Math.round(good * 1000.0 / enabled) / 10.0;
        boolean allowed = enabled > 0 && availability >= 95.0 && missing == 0 && invalid == 0;
        String message = enabled == 0 ? "没有启用测点，不能执行自动健康评估"
                : allowed ? "测点质量满足自动健康评估门槛"
                : "测点质量未达到95%或存在断流/无效数据，健康评估应暂停或降置信度";
        return new DataQualitySummary(normalizedAsset, total, enabled, good, delayed, missing,
                outOfRange, invalid, disabled, availability, allowed, message, clock.instant());
    }

    public DataQualityPointView ingest(String pointCode, PointSampleCommand command) {
        MeasurementPoint point = points.getDomain(pointCode);
        Instant now = clock.instant();
        Instant receivedAt = command.receivedAt() == null ? now : command.receivedAt();
        PointQualitySnapshot previous = quality.findByPointCode(point.code()).orElse(null);
        PointQualitySnapshot assessed = PointQualitySnapshot.assess(point.code(), point.assetCode(), point.enabled(),
                point.sampleIntervalSeconds(), point.lowerLimit(), point.upperLimit(), command.value(),
                command.sourceTimestamp(), receivedAt, previous, now);
        telemetry.write(new TelemetrySeriesPort.TelemetrySample(
                point.code(), point.assetCode(), point.metric(), point.unit(), command.value(),
                assessed.status().name(), command.sourceTimestamp(), receivedAt));
        return DataQualityPointView.compose(point, quality.save(assessed));
    }

    private String requireAsset(String assetCode) {
        String normalized = Asset.normalizeCode(assetCode);
        if (!assets.exists(normalized)) throw new ResourceNotFoundException("未找到设备：" + normalized);
        return normalized;
    }

    private long count(Map<QualityStatus, Long> values, QualityStatus status) {
        return values.getOrDefault(status, 0L);
    }
}
