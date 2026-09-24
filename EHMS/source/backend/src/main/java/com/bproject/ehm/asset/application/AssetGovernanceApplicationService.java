package com.bproject.ehm.asset.application;

import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.asset.domain.model.CalibrationRecord;
import com.bproject.ehm.asset.domain.model.ConfigurationChange;
import com.bproject.ehm.asset.domain.model.DeviceTemplate;
import com.bproject.ehm.asset.ports.CalibrationRecordRepository;
import com.bproject.ehm.asset.ports.ConfigurationChangeRepository;
import com.bproject.ehm.asset.ports.DeviceTemplateRepository;
import com.bproject.ehm.shared.error.DomainConflictException;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import com.bproject.ehm.shared.error.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AssetGovernanceApplicationService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private final DeviceTemplateRepository templates;
    private final CalibrationRecordRepository calibrations;
    private final ConfigurationChangeRepository changes;
    private final AssetQueryFacade assets;
    private final Clock clock;

    @Autowired
    public AssetGovernanceApplicationService(DeviceTemplateRepository templates,
                                             CalibrationRecordRepository calibrations,
                                             ConfigurationChangeRepository changes,
                                             AssetQueryFacade assets) {
        this(templates, calibrations, changes, assets, Clock.systemUTC());
    }

    AssetGovernanceApplicationService(DeviceTemplateRepository templates,
                                      CalibrationRecordRepository calibrations,
                                      ConfigurationChangeRepository changes,
                                      AssetQueryFacade assets, Clock clock) {
        this.templates = templates;
        this.calibrations = calibrations;
        this.changes = changes;
        this.assets = assets;
        this.clock = clock;
    }

    public List<DeviceTemplate> templates(String status) {
        return templates.findAll().stream()
                .filter(value -> blank(status) || value.status().equalsIgnoreCase(status.trim()))
                .sorted(Comparator.comparing(DeviceTemplate::updatedAt).reversed()).toList();
    }

    public DeviceTemplate createTemplate(String code, String name, String deviceType,
                                         List<DeviceTemplate.TemplateComponent> components,
                                         List<DeviceTemplate.TemplatePoint> points,
                                         String inspectionPolicy, String criticality, String createdBy) {
        try {
            String normalized = code == null || code.isBlank() ? "TPL-" + shortId() : code.trim().toUpperCase(Locale.ROOT);
            if (templates.findByCode(normalized).isPresent()) throw new DomainConflictException("模板编码已存在：" + normalized);
            return templates.save(DeviceTemplate.create(normalized, name, deviceType, components, points,
                    inspectionPolicy, criticality, createdBy, clock.instant()));
        } catch (IllegalArgumentException exception) { throw new ValidationException(exception.getMessage()); }
    }

    public DeviceTemplate updateTemplate(String code, String name, String deviceType,
                                         List<DeviceTemplate.TemplateComponent> components,
                                         List<DeviceTemplate.TemplatePoint> points,
                                         String inspectionPolicy, String criticality) {
        try {
            DeviceTemplate current = template(code);
            return templates.save(current.revise(name, deviceType, components, points,
                    inspectionPolicy, criticality, clock.instant()));
        } catch (IllegalArgumentException exception) { throw new ValidationException(exception.getMessage()); }
    }

    public DeviceTemplate publishTemplate(String code, String approver) {
        return templates.save(template(code).publish(approver, clock.instant()));
    }

    public List<CalibrationRecord> calibrations(String assetCode, Boolean expiringWithinDays, Integer days) {
        Instant threshold = Boolean.TRUE.equals(expiringWithinDays)
                ? clock.instant().plusSeconds(Math.max(1, days == null ? 30 : days) * 86400L) : null;
        return calibrations.findAll().stream()
                .filter(value -> blank(assetCode) || value.assetCode().equalsIgnoreCase(assetCode.trim()))
                .filter(value -> threshold == null || value.expiresBefore(threshold))
                .sorted(Comparator.comparing(CalibrationRecord::calibratedAt).reversed()).toList();
    }

    public CalibrationRecord createCalibration(String assetCode, String componentCode, String pointCode,
                                                String sensorCode, String calibrationType,
                                                Double beforeValue, Double afterValue, Double tolerance,
                                                String unit, String result, String certificateNo,
                                                String organization, String operator, Instant calibratedAt,
                                                Instant validUntil, String attachmentRef, String remark) {
        requireAsset(assetCode);
        try {
            Instant now = clock.instant();
            return calibrations.save(CalibrationRecord.create("CAL-" + DAY.format(now) + "-" + shortId(),
                    assetCode, componentCode, pointCode, sensorCode, calibrationType, beforeValue,
                    afterValue, tolerance, unit, result, certificateNo, organization, operator,
                    calibratedAt, validUntil, attachmentRef, remark, now));
        } catch (IllegalArgumentException exception) { throw new ValidationException(exception.getMessage()); }
    }

    public List<ConfigurationChange> changes(String assetCode, String status) {
        return changes.findAll().stream()
                .filter(value -> blank(assetCode) || value.assetCode().equalsIgnoreCase(assetCode.trim()))
                .filter(value -> blank(status) || value.status().equalsIgnoreCase(status.trim()))
                .sorted(Comparator.comparing(ConfigurationChange::createdAt).reversed()).toList();
    }

    public ConfigurationChange createChange(String assetCode, String objectType, String objectCode,
                                             String changeType, Map<String, Object> beforeSnapshot,
                                             Map<String, Object> afterSnapshot, String reason,
                                             String impactAssessment, String applicant,
                                             String rollbackReference) {
        requireAsset(assetCode);
        try {
            Instant now = clock.instant();
            return changes.save(ConfigurationChange.create("CFG-" + DAY.format(now) + "-" + shortId(),
                    assetCode, objectType, objectCode, changeType, beforeSnapshot, afterSnapshot,
                    reason, impactAssessment, applicant, rollbackReference, now));
        } catch (IllegalArgumentException exception) { throw new ValidationException(exception.getMessage()); }
    }

    public ConfigurationChange approveChange(String no, String approver) {
        return changes.save(change(no).approve(approver, clock.instant()));
    }

    public ConfigurationChange applyChange(String no, String executor) {
        return changes.save(change(no).apply(executor, clock.instant()));
    }

    private DeviceTemplate template(String code) {
        return templates.findByCode(code.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("未找到设备模板：" + code));
    }
    private ConfigurationChange change(String no) {
        return changes.findByNo(no.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("未找到配置变更：" + no));
    }
    private void requireAsset(String code) {
        String normalized = Asset.normalizeCode(code);
        if (!assets.exists(normalized)) throw new ResourceNotFoundException("未找到设备：" + normalized);
    }
    private String shortId() { return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
