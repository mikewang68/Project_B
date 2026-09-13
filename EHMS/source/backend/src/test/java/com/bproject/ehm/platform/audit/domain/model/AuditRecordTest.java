package com.bproject.ehm.platform.audit.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditRecordTest {
    @Test
    void derivesOutcomeAndProvidesSafeActorFallbacks() {
        AuditRecord success = AuditRecord.create("AUD-1", Instant.parse("2026-09-08T10:00:00Z"),
                null, null, "POST", "/api/ehm/v1/devices", 201, "trace-1", "127.0.0.1", 15);
        AuditRecord failed = AuditRecord.create("AUD-2", Instant.now(), "测试员", "设备工程师",
                "PUT", "/api/ehm/v1/devices/GT-01", 409, "trace-2", "127.0.0.1", 3);
        assertEquals("SUCCESS", success.outcome());
        assertEquals("未识别用户", success.operator());
        assertEquals("FAILED", failed.outcome());
    }
}
