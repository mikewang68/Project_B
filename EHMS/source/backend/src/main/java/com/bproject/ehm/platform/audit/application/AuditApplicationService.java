package com.bproject.ehm.platform.audit.application;

import com.bproject.ehm.platform.audit.domain.model.AuditRecord;
import com.bproject.ehm.platform.audit.ports.AuditRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AuditApplicationService {
    private final AuditRepository repository;

    public AuditApplicationService(AuditRepository repository) {
        this.repository = repository;
    }

    public AuditRecord record(String operator, String role, String method, String path, int statusCode,
                              String traceId, String clientAddress, long durationMs) {
        AuditRecord record = AuditRecord.create("AUD-" + UUID.randomUUID().toString().toUpperCase(), Instant.now(),
                operator, role, method, path, statusCode, traceId, clientAddress, durationMs);
        return repository.append(record);
    }

    public List<AuditRecord> latest(int limit) {
        return repository.latest(limit);
    }
}
