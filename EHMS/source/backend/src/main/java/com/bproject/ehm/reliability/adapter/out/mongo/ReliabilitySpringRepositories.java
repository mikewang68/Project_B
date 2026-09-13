package com.bproject.ehm.reliability.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

interface FailureModeSpringRepository extends MongoRepository<FailureModeDocument, String> {
    List<FailureModeDocument> findAllByOrderByRpnDesc();
}

interface AlarmRuleSpringRepository extends MongoRepository<AlarmRuleDocument, String> {
    List<AlarmRuleDocument> findAllByOrderByUpdatedAtDesc();
}

interface KnowledgeCaseSpringRepository extends MongoRepository<KnowledgeCaseDocument, String> {
    List<KnowledgeCaseDocument> findAllByOrderByUpdatedAtDesc();
}

interface SlaPolicySpringRepository extends MongoRepository<SlaPolicyDocument, String> {
    List<SlaPolicyDocument> findAllByOrderBySeverityDesc();
}
