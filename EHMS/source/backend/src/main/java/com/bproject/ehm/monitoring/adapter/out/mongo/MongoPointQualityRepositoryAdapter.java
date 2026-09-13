package com.bproject.ehm.monitoring.adapter.out.mongo;

import com.bproject.ehm.monitoring.domain.model.PointQualitySnapshot;
import com.bproject.ehm.monitoring.domain.model.QualityStatus;
import com.bproject.ehm.monitoring.ports.PointQualityRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MongoPointQualityRepositoryAdapter implements PointQualityRepository {
    private final MongoPointQualitySpringRepository repository;
    private final MongoTemplate template;

    public MongoPointQualityRepositoryAdapter(MongoPointQualitySpringRepository repository, MongoTemplate template) {
        this.repository = repository;
        this.template = template;
    }

    @Override
    public Optional<PointQualitySnapshot> findByPointCode(String pointCode) {
        return repository.findById(pointCode).map(PointQualityDocument::toDomain);
    }

    @Override
    public List<PointQualitySnapshot> findByPointCodes(Collection<String> pointCodes) {
        if (pointCodes == null || pointCodes.isEmpty()) return List.of();
        return repository.findAllById(pointCodes).stream().map(PointQualityDocument::toDomain).toList();
    }

    @Override
    public PointQualitySnapshot save(PointQualitySnapshot snapshot) {
        return repository.save(PointQualityDocument.fromDomain(snapshot)).toDomain();
    }

    @Override
    public Map<QualityStatus, Long> countByStatus(String assetCode, Collection<String> activePointCodes) {
        Map<QualityStatus, Long> counts = new EnumMap<>(QualityStatus.class);
        if (activePointCodes == null || activePointCodes.isEmpty()) return counts;
        for (QualityStatus status : QualityStatus.values()) {
            Criteria criteria = new Criteria().andOperator(Criteria.where("assetCode").is(assetCode),
                    Criteria.where("_id").in(activePointCodes), Criteria.where("status").is(status));
            counts.put(status, template.count(Query.query(criteria), PointQualityDocument.class));
        }
        return counts;
    }
}
