package com.bproject.ehm.caseflow.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface MongoMaintenanceCaseSpringRepository extends MongoRepository<MaintenanceCaseDocument, String> {
    Optional<MaintenanceCaseDocument> findByWorkOrderNo(String workOrderNo);
}
