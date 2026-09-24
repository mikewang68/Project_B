package com.bproject.ehm.asset.adapter.in.web;

import com.bproject.ehm.asset.application.AssetGovernanceApplicationService;
import com.bproject.ehm.asset.domain.model.CalibrationRecord;
import com.bproject.ehm.asset.domain.model.ConfigurationChange;
import com.bproject.ehm.asset.domain.model.DeviceTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ehm/v1")
public class AssetGovernanceController {
    private final AssetGovernanceApplicationService service;
    public AssetGovernanceController(AssetGovernanceApplicationService service) { this.service = service; }

    @GetMapping("/device-templates")
    public List<DeviceTemplate> templates(@RequestParam(required = false) String status) { return service.templates(status); }
    @PostMapping("/device-templates") @ResponseStatus(HttpStatus.CREATED)
    public DeviceTemplate createTemplate(@Valid @RequestBody TemplateRequest request) {
        return service.createTemplate(request.templateCode(), request.name(), request.deviceType(),
                request.components(), request.measurementPoints(), request.inspectionPolicy(),
                request.criticality(), request.createdBy());
    }
    @PutMapping("/device-templates/{templateCode}")
    public DeviceTemplate updateTemplate(@PathVariable String templateCode, @Valid @RequestBody TemplateRequest request) {
        return service.updateTemplate(templateCode, request.name(), request.deviceType(), request.components(),
                request.measurementPoints(), request.inspectionPolicy(), request.criticality());
    }
    @PostMapping("/device-templates/{templateCode}/publish")
    public DeviceTemplate publishTemplate(@PathVariable String templateCode,
                                          @RequestBody(required = false) OperatorRequest request) {
        return service.publishTemplate(templateCode, request == null ? null : request.operator());
    }

    @GetMapping("/calibration-records")
    public List<CalibrationRecord> calibrations(@RequestParam(required = false) String assetCode,
                                                @RequestParam(required = false) Boolean expiring,
                                                @RequestParam(required = false) Integer days) {
        return service.calibrations(assetCode, expiring, days);
    }
    @PostMapping("/calibration-records") @ResponseStatus(HttpStatus.CREATED)
    public CalibrationRecord createCalibration(@Valid @RequestBody CalibrationRequest request) {
        return service.createCalibration(request.assetCode(), request.componentCode(), request.pointCode(),
                request.sensorCode(), request.calibrationType(), request.beforeValue(), request.afterValue(),
                request.tolerance(), request.unit(), request.result(), request.certificateNo(), request.organization(),
                request.operator(), request.calibratedAt(), request.validUntil(), request.attachmentRef(), request.remark());
    }

    @GetMapping("/configuration-changes")
    public List<ConfigurationChange> changes(@RequestParam(required = false) String assetCode,
                                             @RequestParam(required = false) String status) {
        return service.changes(assetCode, status);
    }
    @PostMapping("/configuration-changes") @ResponseStatus(HttpStatus.CREATED)
    public ConfigurationChange createChange(@Valid @RequestBody ChangeRequest request) {
        return service.createChange(request.assetCode(), request.objectType(), request.objectCode(), request.changeType(),
                request.beforeSnapshot(), request.afterSnapshot(), request.reason(), request.impactAssessment(),
                request.applicant(), request.rollbackReference());
    }
    @PostMapping("/configuration-changes/{changeNo}/approve")
    public ConfigurationChange approve(@PathVariable String changeNo,
                                       @RequestBody(required = false) OperatorRequest request) {
        return service.approveChange(changeNo, request == null ? null : request.operator());
    }
    @PostMapping("/configuration-changes/{changeNo}/apply")
    public ConfigurationChange apply(@PathVariable String changeNo,
                                     @RequestBody(required = false) OperatorRequest request) {
        return service.applyChange(changeNo, request == null ? null : request.operator());
    }

    public record TemplateRequest(String templateCode, @NotBlank String name, @NotBlank String deviceType,
                                  List<DeviceTemplate.TemplateComponent> components,
                                  List<DeviceTemplate.TemplatePoint> measurementPoints,
                                  String inspectionPolicy, String criticality, String createdBy) {}
    public record CalibrationRequest(@NotBlank String assetCode, String componentCode, String pointCode,
                                     @NotBlank String sensorCode, String calibrationType,
                                     Double beforeValue, Double afterValue, Double tolerance, String unit,
                                     String result, String certificateNo, String organization, String operator,
                                     Instant calibratedAt, Instant validUntil, String attachmentRef, String remark) {}
    public record ChangeRequest(@NotBlank String assetCode, String objectType,
                                @NotBlank String objectCode, String changeType,
                                Map<String, Object> beforeSnapshot,
                                Map<String, Object> afterSnapshot,
                                @NotBlank String reason, String impactAssessment,
                                String applicant, String rollbackReference) {}
    public record OperatorRequest(String operator) {}
}
