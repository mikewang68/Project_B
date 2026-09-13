package com.bproject.ehm.maintenance.adapter.out.mongo;

import com.bproject.ehm.maintenance.domain.model.InspectionTask;
import com.bproject.ehm.maintenance.ports.InspectionTaskRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MongoInspectionTaskRepositoryAdapter implements InspectionTaskRepository {
    private final MongoInspectionTaskSpringRepository repository;

    public MongoInspectionTaskRepositoryAdapter(MongoInspectionTaskSpringRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<InspectionTask> findByTaskNo(String taskNo) {
        return repository.findById(taskNo).map(InspectionTaskDocument::toDomain);
    }

    @Override
    public List<InspectionTask> findByAssetCode(String assetCode) {
        return repository.findByAssetCodeOrderByScheduledAtDesc(assetCode).stream()
                .map(InspectionTaskDocument::toDomain).toList();
    }

    @Override
    public boolean hasOpenTaskForPlan(String planId) {
        return repository.existsByPlanIdAndStatusIn(planId, List.of("PLANNED", "IN_PROGRESS"));
    }

    @Override
    public InspectionTask save(InspectionTask task) {
        return repository.save(InspectionTaskDocument.fromDomain(task)).toDomain();
    }
}
