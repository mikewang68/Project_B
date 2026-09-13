package com.bproject.ehm.monitoring.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

interface MongoPointQualitySpringRepository extends MongoRepository<PointQualityDocument, String> {
}
