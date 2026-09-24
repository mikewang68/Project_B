package com.bproject.ehm.asset.domain.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalibrationRecordTest {
    @Test void validUntilMustBeAfterCalibrationTime() {
        Instant now = Instant.parse("2026-09-24T02:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> CalibrationRecord.create("CAL-1", "GT-01",
                null, "VIB", "SEN-1", "周期校准", 1.0, 1.0, 0.1, "mm/s", "PASS",
                null, null, null, now, now.minusSeconds(1), null, null, now));
    }
}
