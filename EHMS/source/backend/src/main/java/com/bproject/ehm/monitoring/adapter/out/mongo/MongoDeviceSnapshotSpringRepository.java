package com.bproject.ehm.monitoring.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

interface MongoDeviceSnapshotSpringRepository extends MongoRepository<DeviceSnapshotDocument, String> {
}
