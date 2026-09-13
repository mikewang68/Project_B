package com.bproject.ehm.maintenance.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkOrderTest {
    private static final Instant NOW = Instant.parse("2026-09-07T02:00:00Z");

    @Test
    void workOrderFollowsApprovalExecutionAndVerificationFlow() {
        WorkOrder order = WorkOrder.create("WO-1", "GT-01", "门吊", "检查减速机", "P1 高",
                "机修班", "告警", "复测振动", "夜班", "tester", NOW);

        order = order.transitionTo(WorkOrderStatus.APPROVED, "approver", "审批通过", NOW.plusSeconds(10));
        order = order.transitionTo(WorkOrderStatus.IN_PROGRESS, "worker", "开始执行", NOW.plusSeconds(20));
        order = order.transitionTo(WorkOrderStatus.WAITING_VERIFY, "worker", "维修完成", NOW.plusSeconds(30));
        order = order.transitionTo(WorkOrderStatus.CLOSED, "verifier", "复测通过", NOW.plusSeconds(40));

        assertEquals(WorkOrderStatus.CLOSED, order.status());
        assertEquals(5, order.history().size());
    }

    @Test
    void submittedOrderCannotJumpDirectlyToClosed() {
        WorkOrder order = WorkOrder.create("WO-1", "GT-01", "门吊", "检查减速机", "P1 高",
                "机修班", "告警", "复测振动", "夜班", "tester", NOW);
        assertThrows(DomainConflictException.class,
                () -> order.transitionTo(WorkOrderStatus.CLOSED, "tester", "跳步", NOW.plusSeconds(1)));
    }
}
