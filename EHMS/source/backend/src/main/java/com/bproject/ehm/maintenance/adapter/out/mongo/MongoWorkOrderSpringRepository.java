package com.bproject.ehm.maintenance.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

interface MongoWorkOrderSpringRepository extends MongoRepository<WorkOrderDocument, String> {
}
