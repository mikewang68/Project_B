package com.bproject.ehm.maintenance.application;

import com.bproject.ehm.maintenance.domain.model.DefectRecord;
import com.bproject.ehm.maintenance.domain.model.InspectionTask;

public record InspectionSubmissionResult(
        InspectionTask task,
        DefectRecord defect
) {
}
