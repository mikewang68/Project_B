package com.bproject.ehm.reliability.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void verificationIsRecordedWithoutOverwritingCase() {
        KnowledgeCase verified = value().verify("设备主管", NOW.plusSeconds(1));
        assertEquals("VERIFIED", verified.status());
        assertEquals("设备主管", verified.verifiedBy());
    }

    @Test
    void verifiedCaseCannotBeVerifiedAgain() {
        KnowledgeCase verified = value().verify("设备主管", NOW);
        assertThrows(DomainConflictException.class, () -> verified.verify("另一人", NOW.plusSeconds(1)));
    }

    private KnowledgeCase value() {
        return KnowledgeCase.create("KB-1", "GT-GEAR-001", "门吊", "减速机", "轴承案例",
                "振动升高", "润滑劣化", List.of("校验传感器", "采集油样"),
                "换油并复测", "振动回到基线", "WO-1", NOW);
    }
}
