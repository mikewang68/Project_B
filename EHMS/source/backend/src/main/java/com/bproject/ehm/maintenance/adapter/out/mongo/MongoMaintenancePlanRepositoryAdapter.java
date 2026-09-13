package com.bproject.ehm.maintenance.adapter.out.mongo;

import com.bproject.ehm.maintenance.domain.model.MaintenancePlan;
import com.bproject.ehm.maintenance.ports.MaintenancePlanRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MongoMaintenancePlanRepositoryAdapter implements MaintenancePlanRepository {
    private final MongoMaintenancePlanSpringRepository repository;

    public MongoMaintenancePlanRepositoryAdapter(MongoMaintenancePlanSpringRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<MaintenancePlan> findById(String planId) {
        return repository.findById(planId).map(MaintenancePlanDocument::toDomain);
    }

    @Override
    public List<MaintenancePlan> findByAssetCode(String assetCode) {
        return repository.findByAssetCodeOrderByNextDueAtAsc(assetCode).stream()
                .map(MaintenancePlanDocument::toDomain).toList();
    }

    @Override
    public MaintenancePlan save(MaintenancePlan plan) {
        return repository.save(MaintenancePlanDocument.fromDomain(plan)).toDomain();
    }
}
