package com.bproject.ehm.maintenance.adapter.out.mongo;

import com.bproject.ehm.maintenance.domain.model.InspectionItemResult;
import com.bproject.ehm.maintenance.domain.model.InspectionTask;
import com.bproject.ehm.maintenance.domain.model.InspectionTaskStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document("inspection_tasks")
public class InspectionTaskDocument {
    @Id private final String taskNo;
    @Indexed private final String planId;
    @Indexed private final String assetCode;
    private final String assetName;
    private final String componentCode;
    private final String title;
    @Indexed private final Instant scheduledAt;
    private final String assignee;
    @Indexed private final String status;
    private final List<InspectionItemResult> checklist;
    private final Instant startedAt;
    private final Instant submittedAt;
    private final String conclusion;
    private final String operator;
    @Indexed(sparse = true) private final String defectNo;
    private final Instant createdAt;
    private final Instant updatedAt;
    @Version private final Long version;

    public InspectionTaskDocument(String taskNo, String planId, String assetCode, String assetName,
                                  String componentCode, String title, Instant scheduledAt,
                                  String assignee, String status, List<InspectionItemResult> checklist,
                                  Instant startedAt, Instant submittedAt, String conclusion,
                                  String operator, String defectNo, Instant createdAt,
                                  Instant updatedAt, Long version) {
        this.taskNo = taskNo;
        this.planId = planId;
        this.assetCode = assetCode;
        this.assetName = assetName;
        this.componentCode = componentCode;
        this.title = title;
        this.scheduledAt = scheduledAt;
        this.assignee = assignee;
        this.status = status;
        this.checklist = checklist;
        this.startedAt = startedAt;
        this.submittedAt = submittedAt;
        this.conclusion = conclusion;
        this.operator = operator;
        this.defectNo = defectNo;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    static InspectionTaskDocument fromDomain(InspectionTask value) {
        return new InspectionTaskDocument(value.taskNo(), value.planId(), value.assetCode(),
                value.assetName(), value.componentCode(), value.title(), value.scheduledAt(),
                value.assignee(), value.status().name(), value.checklist(), value.startedAt(),
                value.submittedAt(), value.conclusion(), value.operator(), value.defectNo(),
                value.createdAt(), value.updatedAt(), value.version());
    }

    InspectionTask toDomain() {
        return new InspectionTask(taskNo, planId, assetCode, assetName, componentCode, title,
                scheduledAt, assignee, InspectionTaskStatus.from(status), checklist, startedAt,
                submittedAt, conclusion, operator, defectNo, createdAt, updatedAt, version);
    }
}
