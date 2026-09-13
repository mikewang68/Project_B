package com.bproject.ehm.health.ports;

import com.bproject.ehm.health.domain.model.HealthAssessment;

import java.util.List;
import java.util.Optional;

public interface HealthAssessmentRepository {
    Optional<HealthAssessment> findById(String assessmentId);

    Optional<HealthAssessment> findLatestByAssetCode(String assetCode);

    List<HealthAssessment> findRecentByAssetCode(String assetCode, int limit);

    HealthAssessment save(HealthAssessment assessment);
}
