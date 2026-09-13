package com.bproject.ehm.spares.adapter.out.mongo;

import com.bproject.ehm.spares.domain.model.SparePart;
import com.bproject.ehm.spares.ports.SparePartRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MongoSparePartRepositoryAdapter implements SparePartRepository {
    private final MongoSparePartSpringRepository repository;

    public MongoSparePartRepositoryAdapter(MongoSparePartSpringRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<SparePart> findByPartCode(String partCode) {
        return repository.findById(SparePart.normalize(partCode)).map(SparePartDocument::toDomain);
    }

    @Override
    public List<SparePart> findAllActive() {
        return repository.findByActiveTrueOrderByPartCodeAsc().stream().map(SparePartDocument::toDomain).toList();
    }

    @Override
    public SparePart save(SparePart part) {
        return repository.save(SparePartDocument.fromDomain(part)).toDomain();
    }
}
