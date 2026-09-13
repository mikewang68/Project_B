package com.bproject.ehm.platform.audit.adapter.out.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("platform_audit_logs")
record AuditDocument(
        @Id String auditId, @Indexed Instant occurredAt, String operator, String role,
        String method, String path, int statusCode, String outcome,
        String traceId, String clientAddress, long durationMs
) {}
