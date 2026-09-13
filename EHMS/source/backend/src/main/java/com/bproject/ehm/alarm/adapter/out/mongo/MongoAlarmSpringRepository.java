package com.bproject.ehm.alarm.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

interface MongoAlarmSpringRepository extends MongoRepository<AlarmDocument, String> {
}
