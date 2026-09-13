package com.bproject.ehm.maintenance.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MongoDefectSpringRepository extends MongoRepository<DefectDocument, String> {
    List<DefectDocument> findByAssetCodeOrderByDiscoveredAtDesc(String assetCode);
}
