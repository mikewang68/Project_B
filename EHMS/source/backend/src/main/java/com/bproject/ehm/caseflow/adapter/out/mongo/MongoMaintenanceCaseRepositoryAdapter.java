package com.bproject.ehm.caseflow.adapter.out.mongo;

import com.bproject.ehm.caseflow.domain.model.MaintenanceCase;
import com.bproject.ehm.caseflow.ports.MaintenanceCaseRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class MongoMaintenanceCaseRepositoryAdapter implements MaintenanceCaseRepository {
    private final MongoMaintenanceCaseSpringRepository repository;

    public MongoMaintenanceCaseRepositoryAdapter(MongoMaintenanceCaseSpringRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<MaintenanceCase> findByAlarmNo(String alarmNo) {
        return repository.findById(alarmNo).map(MaintenanceCaseDocument::toDomain);
    }

    @Override
    public Optional<MaintenanceCase> findByWorkOrderNo(String workOrderNo) {
        return repository.findByWorkOrderNo(workOrderNo).map(MaintenanceCaseDocument::toDomain);
    }

    @Override
    public MaintenanceCase save(MaintenanceCase maintenanceCase) {
        return repository.save(MaintenanceCaseDocument.fromDomain(maintenanceCase)).toDomain();
    }
}
