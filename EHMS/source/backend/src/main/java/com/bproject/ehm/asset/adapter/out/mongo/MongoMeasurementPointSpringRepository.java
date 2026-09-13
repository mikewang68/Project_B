package com.bproject.ehm.asset.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

interface MongoMeasurementPointSpringRepository extends MongoRepository<MeasurementPointDocument, String> {
}
