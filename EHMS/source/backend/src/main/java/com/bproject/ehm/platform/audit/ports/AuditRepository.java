package com.bproject.ehm.platform.audit.ports;

import com.bproject.ehm.platform.audit.domain.model.AuditRecord;

import java.util.List;

public interface AuditRepository {
    AuditRecord append(AuditRecord record);
    List<AuditRecord> latest(int limit);
}
