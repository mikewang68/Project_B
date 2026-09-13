package com.bproject.ehm.platform.audit.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

interface MongoAuditSpringRepository extends MongoRepository<AuditDocument, String> {
    List<AuditDocument> findTop200ByOrderByOccurredAtDesc();
}
