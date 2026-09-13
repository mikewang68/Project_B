package com.bproject.ehm.maintenance.adapter.out.mongo;

import com.bproject.ehm.maintenance.domain.model.DefectRecord;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("maintenance_defects")
public class DefectDocument {
    @Id private final String defectNo;
    @Indexed(unique = true) private final String taskNo;
    @Indexed private final String assetCode;
    private final String assetName;
    private final String componentCode;
    private final String severity;
    private final String description;
    private final String evidenceSummary;
    @Indexed private final String status;
    @Indexed(sparse = true) private final String workOrderNo;
    private final String reporter;
    private final Instant discoveredAt;
    private final Instant updatedAt;
    @Version private final Long version;

    public DefectDocument(String defectNo, String taskNo, String assetCode, String assetName,
                          String componentCode, String severity, String description,
                          String evidenceSummary, String status, String workOrderNo,
                          String reporter, Instant discoveredAt, Instant updatedAt, Long version) {
        this.defectNo = defectNo;
        this.taskNo = taskNo;
        this.assetCode = assetCode;
        this.assetName = assetName;
        this.componentCode = componentCode;
        this.severity = severity;
        this.description = description;
        this.evidenceSummary = evidenceSummary;
        this.status = status;
        this.workOrderNo = workOrderNo;
        this.reporter = reporter;
        this.discoveredAt = discoveredAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    static DefectDocument fromDomain(DefectRecord value) {
        return new DefectDocument(value.defectNo(), value.taskNo(), value.assetCode(), value.assetName(),
                value.componentCode(), value.severity(), value.description(), value.evidenceSummary(),
                value.status(), value.workOrderNo(), value.reporter(), value.discoveredAt(),
                value.updatedAt(), value.version());
    }

    DefectRecord toDomain() {
        return new DefectRecord(defectNo, taskNo, assetCode, assetName, componentCode, severity,
                description, evidenceSummary, status, workOrderNo, reporter, discoveredAt,
                updatedAt, version);
    }
}
