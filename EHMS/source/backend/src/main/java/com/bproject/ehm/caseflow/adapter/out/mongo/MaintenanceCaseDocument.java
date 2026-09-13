package com.bproject.ehm.caseflow.adapter.out.mongo;

import com.bproject.ehm.caseflow.domain.model.CaseStatus;
import com.bproject.ehm.caseflow.domain.model.DiagnosisRecord;
import com.bproject.ehm.caseflow.domain.model.EvidenceItem;
import com.bproject.ehm.caseflow.domain.model.ExecutionRecord;
import com.bproject.ehm.caseflow.domain.model.MaintenanceCase;
import com.bproject.ehm.caseflow.domain.model.RetestRecord;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document("maintenance_cases")
public class MaintenanceCaseDocument {
    @Id private final String alarmNo;
    private final String deviceCode;
    private final String deviceName;
    private final String component;
    private final String alarmLevel;
    private final String alarmSummary;
    private final String status;
    private final List<EvidenceItem> evidence;
    private final List<DiagnosisRecord> diagnoses;
    @Indexed(sparse = true) private final String workOrderNo;
    private final List<ExecutionRecord> executionRecords;
    private final List<RetestRecord> retests;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant closedAt;
    @Version private final Long version;

    public MaintenanceCaseDocument(String alarmNo, String deviceCode, String deviceName, String component,
                                   String alarmLevel, String alarmSummary, String status,
                                   List<EvidenceItem> evidence, List<DiagnosisRecord> diagnoses,
                                   String workOrderNo, List<ExecutionRecord> executionRecords,
                                   List<RetestRecord> retests, Instant createdAt, Instant updatedAt,
                                   Instant closedAt, Long version) {
        this.alarmNo = alarmNo;
        this.deviceCode = deviceCode;
        this.deviceName = deviceName;
        this.component = component;
        this.alarmLevel = alarmLevel;
        this.alarmSummary = alarmSummary;
        this.status = status;
        this.evidence = evidence;
        this.diagnoses = diagnoses;
        this.workOrderNo = workOrderNo;
        this.executionRecords = executionRecords;
        this.retests = retests;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.closedAt = closedAt;
        this.version = version;
    }

    public static MaintenanceCaseDocument fromDomain(MaintenanceCase value) {
        return new MaintenanceCaseDocument(value.alarmNo(), value.deviceCode(), value.deviceName(),
                value.component(), value.alarmLevel(), value.alarmSummary(), value.status().name(),
                value.evidence(), value.diagnoses(), value.workOrderNo(), value.executionRecords(),
                value.retests(), value.createdAt(), value.updatedAt(), value.closedAt(), value.version());
    }

    public MaintenanceCase toDomain() {
        return new MaintenanceCase(alarmNo, deviceCode, deviceName, component, alarmLevel, alarmSummary,
                CaseStatus.from(status), evidence, diagnoses, workOrderNo, executionRecords, retests,
                createdAt, updatedAt, closedAt, version);
    }
}
