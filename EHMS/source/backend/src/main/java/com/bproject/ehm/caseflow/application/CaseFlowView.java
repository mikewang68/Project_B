package com.bproject.ehm.caseflow.application;

import com.bproject.ehm.caseflow.domain.model.DiagnosisRecord;
import com.bproject.ehm.caseflow.domain.model.EvidenceItem;
import com.bproject.ehm.caseflow.domain.model.ExecutionRecord;
import com.bproject.ehm.caseflow.domain.model.MaintenanceCase;
import com.bproject.ehm.caseflow.domain.model.RetestRecord;

import java.time.Instant;
import java.util.List;

public record CaseFlowView(
        String alarmNo,
        String deviceCode,
        String deviceName,
        String component,
        String alarmLevel,
        String alarmSummary,
        String status,
        String statusCode,
        List<EvidenceItem> evidence,
        List<DiagnosisRecord> diagnoses,
        String workOrderNo,
        List<ExecutionRecord> executionRecords,
        List<RetestRecord> retests,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        Long version
) {
    public static CaseFlowView from(MaintenanceCase value) {
        return new CaseFlowView(value.alarmNo(), value.deviceCode(), value.deviceName(), value.component(),
                value.alarmLevel(), value.alarmSummary(), value.status().label(), value.status().name(),
                value.evidence(), value.diagnoses(), value.workOrderNo(), value.executionRecords(),
                value.retests(), value.createdAt(), value.updatedAt(), value.closedAt(), value.version());
    }
}
