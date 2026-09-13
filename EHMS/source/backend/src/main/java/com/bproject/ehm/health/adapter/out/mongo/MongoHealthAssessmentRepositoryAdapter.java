package com.bproject.ehm.health.adapter.out.mongo;

import com.bproject.ehm.health.domain.model.HealthAssessment;
import com.bproject.ehm.health.ports.HealthAssessmentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MongoHealthAssessmentRepositoryAdapter implements HealthAssessmentRepository {
    private final MongoHealthAssessmentSpringRepository repository;

    public MongoHealthAssessmentRepositoryAdapter(MongoHealthAssessmentSpringRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<HealthAssessment> findById(String assessmentId) {
        return repository.findById(assessmentId).map(HealthAssessmentDocument::toDomain);
    }

    @Override
    public Optional<HealthAssessment> findLatestByAssetCode(String assetCode) {
        return repository.findTopByAssetCodeOrderByGeneratedAtDesc(assetCode)
                .map(HealthAssessmentDocument::toDomain);
    }

    @Override
    public List<HealthAssessment> findRecentByAssetCode(String assetCode, int limit) {
        return repository.findByAssetCodeOrderByGeneratedAtDesc(assetCode,
                        PageRequest.of(0, Math.max(1, Math.min(limit, 100)))).stream()
                .map(HealthAssessmentDocument::toDomain).toList();
    }

    @Override
    public HealthAssessment save(HealthAssessment assessment) {
        return repository.save(HealthAssessmentDocument.fromDomain(assessment)).toDomain();
    }
}
