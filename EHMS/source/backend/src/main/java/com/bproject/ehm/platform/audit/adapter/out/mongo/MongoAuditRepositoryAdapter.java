package com.bproject.ehm.platform.audit.adapter.out.mongo;

import com.bproject.ehm.platform.audit.domain.model.AuditRecord;
import com.bproject.ehm.platform.audit.ports.AuditRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MongoAuditRepositoryAdapter implements AuditRepository {
    private final MongoAuditSpringRepository repository;

    public MongoAuditRepositoryAdapter(MongoAuditSpringRepository repository) {
        this.repository = repository;
    }

    @Override
    public AuditRecord append(AuditRecord record) {
        return toDomain(repository.save(toDocument(record)));
    }

    @Override
    public List<AuditRecord> latest(int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return repository.findTop200ByOrderByOccurredAtDesc().stream().limit(bounded).map(this::toDomain).toList();
    }

    private AuditDocument toDocument(AuditRecord record) {
        return new AuditDocument(record.auditId(), record.occurredAt(), record.operator(), record.role(),
                record.method(), record.path(), record.statusCode(), record.outcome(), record.traceId(),
                record.clientAddress(), record.durationMs());
    }

    private AuditRecord toDomain(AuditDocument document) {
        return new AuditRecord(document.auditId(), document.occurredAt(), document.operator(), document.role(),
                document.method(), document.path(), document.statusCode(), document.outcome(), document.traceId(),
                document.clientAddress(), document.durationMs());
    }
}
