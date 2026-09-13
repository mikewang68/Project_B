package com.bproject.ehm.maintenance.ports;

import com.bproject.ehm.maintenance.domain.model.InspectionTask;

import java.util.List;
import java.util.Optional;

public interface InspectionTaskRepository {
    Optional<InspectionTask> findByTaskNo(String taskNo);

    List<InspectionTask> findByAssetCode(String assetCode);

    boolean hasOpenTaskForPlan(String planId);

    InspectionTask save(InspectionTask task);
}
