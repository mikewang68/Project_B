package com.bproject.ehm.health.adapter.out.mongo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface MongoHealthAssessmentSpringRepository extends MongoRepository<HealthAssessmentDocument, String> {
    Optional<HealthAssessmentDocument> findTopByAssetCodeOrderByGeneratedAtDesc(String assetCode);

    List<HealthAssessmentDocument> findByAssetCodeOrderByGeneratedAtDesc(String assetCode, Pageable pageable);
}
