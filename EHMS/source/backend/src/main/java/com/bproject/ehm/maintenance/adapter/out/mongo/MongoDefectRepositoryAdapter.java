package com.bproject.ehm.maintenance.adapter.out.mongo;

import com.bproject.ehm.maintenance.domain.model.DefectRecord;
import com.bproject.ehm.maintenance.ports.DefectRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MongoDefectRepositoryAdapter implements DefectRepository {
    private final MongoDefectSpringRepository repository;

    public MongoDefectRepositoryAdapter(MongoDefectSpringRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<DefectRecord> findByDefectNo(String defectNo) {
        return repository.findById(defectNo).map(DefectDocument::toDomain);
    }

    @Override
    public List<DefectRecord> findByAssetCode(String assetCode) {
        return repository.findByAssetCodeOrderByDiscoveredAtDesc(assetCode).stream()
                .map(DefectDocument::toDomain).toList();
    }

    @Override
    public DefectRecord save(DefectRecord defect) {
        return repository.save(DefectDocument.fromDomain(defect)).toDomain();
    }
}
