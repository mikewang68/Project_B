package com.bproject.ehm.maintenance.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface MongoInspectionTaskSpringRepository extends MongoRepository<InspectionTaskDocument, String> {
    List<InspectionTaskDocument> findByAssetCodeOrderByScheduledAtDesc(String assetCode);

    boolean existsByPlanIdAndStatusIn(String planId, Collection<String> statuses);
}
