package com.bproject.ehm.caseflow.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaintenanceCaseTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void requiresManualDiagnosisBeforeCreatingWorkOrder() {
        MaintenanceCase value = opened();

        assertThrows(DomainConflictException.class,
                () -> value.linkWorkOrder("WO-1", NOW.plusSeconds(1)));
    }

    @Test
    void recordsTraceableDiagnosisExecutionAndRetest() {
        MaintenanceCase diagnosed = opened().diagnose("确认需要检修", "润滑状态异常", "中",
                "现场复测与油样结果一致", "设备工程师", NOW.plusSeconds(1));
        MaintenanceCase linked = diagnosed.linkWorkOrder("WO-1", NOW.plusSeconds(2));
        MaintenanceCase executed = linked.recordExecution("补充润滑并校正联轴器", "振动下降",
                "已完成停机、隔离、挂牌确认", "润滑脂1盒", "机修员", NOW.plusSeconds(3));
        MaintenanceCase verified = executed.recordRetest("GT01-VIB-RMS", 6.8, 3.1,
                "mm/s", "维修后振动速度RMS小于4.5mm/s", true, "设备工程师", NOW.plusSeconds(4));

        assertEquals(CaseStatus.VERIFIED, verified.status());
        assertEquals(1, verified.diagnoses().size());
        assertEquals(1, verified.executionRecords().size());
        assertEquals(1, verified.retests().size());
    }

    @Test
    void refusesRetestWithoutExecutionRecord() {
        MaintenanceCase linked = opened()
                .diagnose("需要检修", "轴承异常", "中", "现场复核", "工程师", NOW.plusSeconds(1))
                .linkWorkOrder("WO-1", NOW.plusSeconds(2));

        assertThrows(DomainConflictException.class, () -> linked.recordRetest("P-1", 8.0, 4.0,
                "mm/s", "小于4.5mm/s", true, "工程师", NOW.plusSeconds(3)));
    }

    @Test
    void refusesClosureWhenNoRetestPassed() {
        MaintenanceCase failed = opened()
                .diagnose("需要检修", "轴承异常", "中", "现场复核", "工程师", NOW.plusSeconds(1))
                .linkWorkOrder("WO-1", NOW.plusSeconds(2))
                .recordExecution("更换轴承", "完成", "隔离挂牌完成", "轴承1套", "机修员", NOW.plusSeconds(3))
                .recordRetest("P-1", 8.0, 6.0, "mm/s", "小于4.5mm/s", false,
                        "工程师", NOW.plusSeconds(4));

        assertThrows(DomainConflictException.class,
                () -> failed.close("工程师", "维修完成", NOW.plusSeconds(5)));
    }

    @Test
    void closesOnlyAfterExecutionAndPassedRetest() {
        MaintenanceCase verified = opened()
                .diagnose("需要检修", "轴承异常", "高", "频谱与油样支持", "工程师", NOW.plusSeconds(1))
                .linkWorkOrder("WO-1", NOW.plusSeconds(2))
                .recordExecution("更换轴承", "完成", "隔离挂牌完成", "轴承1套", "机修员", NOW.plusSeconds(3))
                .recordRetest("P-1", 8.0, 3.2, "mm/s", "小于4.5mm/s", true,
                        "工程师", NOW.plusSeconds(4));

        MaintenanceCase closed = verified.close("设备主管", "复测通过并恢复运行", NOW.plusSeconds(5));

        assertEquals(CaseStatus.CLOSED, closed.status());
        assertNotNull(closed.closedAt());
        assertEquals("CLOSURE", closed.evidence().get(closed.evidence().size() - 1).type());
    }

    private MaintenanceCase opened() {
        return MaintenanceCase.open("ALM-1", "GT-01", "1#门式起重机", "起升减速机",
                "L3 严重", "振动异常", List.of(new EvidenceItem("ALARM", "告警", "振动异常", "ALM-1", NOW)), NOW);
    }
}
