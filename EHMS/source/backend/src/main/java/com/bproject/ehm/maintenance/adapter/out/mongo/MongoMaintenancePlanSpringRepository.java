package com.bproject.ehm.maintenance.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MongoMaintenancePlanSpringRepository extends MongoRepository<MaintenancePlanDocument, String> {
    List<MaintenancePlanDocument> findByAssetCodeOrderByNextDueAtAsc(String assetCode);
}
