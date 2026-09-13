package com.bproject.ehm.health.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HealthAssessmentTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void derivesGradeAndClampsScore() {
        HealthAssessment value = assessment(108);
        assertEquals(100, value.healthScore());
        assertEquals("健康", value.healthGrade());
    }

    @Test
    void acceptedReviewCanLinkWorkOrder() {
        HealthAssessment reviewed = assessment(68).review("ACCEPTED", "现场核验后接受建议", "设备工程师", NOW);
        HealthAssessment linked = reviewed.linkWorkOrder("WO-001", NOW.plusSeconds(1));
        assertEquals("WO-001", linked.workOrderNo());
        assertEquals("ACCEPTED", linked.review().decision());
    }

    @Test
    void predictionCannotCreateWorkOrderBeforeReview() {
        assertThrows(DomainConflictException.class, () -> assessment(68).linkWorkOrder("WO-001", NOW));
    }

    @Test
    void reviewRecordCannotBeOverwritten() {
        HealthAssessment reviewed = assessment(68).review("OBSERVE", "继续观察", "设备工程师", NOW);
        assertThrows(DomainConflictException.class,
                () -> reviewed.review("ACCEPTED", "改为接受", "设备主管", NOW.plusSeconds(1)));
    }

    private HealthAssessment assessment(int score) {
        return HealthAssessment.completed("HA-001", "GT-01", "1#门式起重机", score, 75,
                "规则基线", "model-v1", "feature-v1", 98.6,
                List.of(new HealthFactor("P-1", "振动", 6.8, "mm/s", 7.1, 95.8, 22, "接近上限")),
                new RulPrediction("DEMO_ESTIMATE", "rul-v1", 16, 21, 27, 70,
                        "高", "性能劣化", "7天内", "人工核验", "仅Demo"),
                List.of("仅用于Demo"), NOW.minusSeconds(1800), NOW, NOW.plusSeconds(1800));
    }
}
