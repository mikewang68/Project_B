package com.bproject.safety.support.phase1;

import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.model.PendingEventStatuses;
import com.bproject.safety.module.ops.repository.EdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.EdgeNodeRepository;
import com.bproject.safety.module.ops.service.EdgeOpsService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Step 1.5: EdgeOpsService 事务代理边界测试（P1-2）")
class EdgeOpsServiceTransactionTest {

    @Autowired
    private EdgeOpsService opsService;

    @MockitoSpyBean
    private EdgeNodeRepository nodeRepository;

    @MockitoSpyBean
    private EdgeEventQueueRepository queueRepository;

    @BeforeEach
    void setUp() {
        DemoEdgeNode node2 = new DemoEdgeNode();
        node2.id = "EDGE-02";
        node2.name = "边缘节点 02";
        node2.area = "装卸区 B";
        node2.status = "ONLINE";
        node2.cloudConnected = true;
        node2.diskUsage = 40;
        nodeRepository.save(node2);

        DemoEdgeNode node3 = new DemoEdgeNode();
        node3.id = "EDGE-03";
        node3.name = "边缘节点 03";
        node3.area = "装卸区 C";
        node3.status = "ONLINE";
        node3.cloudConnected = true;
        node3.diskUsage = 40;
        nodeRepository.save(node3);
    }

    @Test
    @DisplayName("simulate('timeDrift'): 公共编排入口开启事务，底层 nodeRepository.save 处于事务上下文中")
    void testSimulateTimeDriftActiveTransaction() {
        AtomicBoolean txActiveAtSave = new AtomicBoolean(false);
        doAnswer(inv -> {
            txActiveAtSave.set(TransactionSynchronizationManager.isActualTransactionActive());
            return inv.callRealMethod();
        }).when(nodeRepository).save(any());

        Map<String, Object> result = opsService.simulate("timeDrift", "EDGE-02");

        assertThat(result).isNotNull();
        assertThat(txActiveAtSave.get())
                .as("nodeRepository.save 必须在激活的事务中执行")
                .isTrue();
    }

    @Test
    @DisplayName("simulate('cacheAlert'): 消除 private @Transactional 绕过，nodeRepository.save 处于事务上下文中")
    void testSimulateCacheAlertActiveTransaction() {
        AtomicBoolean txActiveAtSave = new AtomicBoolean(false);
        doAnswer(inv -> {
            txActiveAtSave.set(TransactionSynchronizationManager.isActualTransactionActive());
            return inv.callRealMethod();
        }).when(nodeRepository).save(any());

        Map<String, Object> result = opsService.simulate("cacheAlert", "EDGE-03");

        assertThat(result).isNotNull();
        assertThat(txActiveAtSave.get())
                .as("nodeRepository.save 必须在激活的事务中执行")
                .isTrue();
    }

    @Test
    @DisplayName("simulate('replayFailure'): 底层 armFailure 与 queueRepository.save 处于事务上下文中")
    void testSimulateReplayFailureActiveTransaction() {
        EdgePendingEvent event = new EdgePendingEvent();
        event.eventId = "EVT-TEST-001";
        event.edgeNodeId = "EDGE-03";
        event.eventType = "person-intrusion";
        event.status = PendingEventStatuses.PENDING;
        event.edgeOccurredAt = OffsetDateTime.now();
        queueRepository.save(event);

        AtomicBoolean txActiveAtSave = new AtomicBoolean(false);
        doAnswer(inv -> {
            txActiveAtSave.set(TransactionSynchronizationManager.isActualTransactionActive());
            return inv.callRealMethod();
        }).when(queueRepository).save(any());

        Map<String, Object> result = opsService.simulate("replayFailure", "EVT-TEST-001");

        assertThat(result).isNotNull();
        assertThat(txActiveAtSave.get())
                .as("queueRepository.save 必须在激活的事务中执行")
                .isTrue();
    }

    @Test
    @DisplayName("直接调用 simulateTimeDrift: 公共方法独立拥有 @Transactional 边界")
    void testDirectSimulateTimeDriftActiveTransaction() {
        AtomicBoolean txActiveAtSave = new AtomicBoolean(false);
        doAnswer(inv -> {
            txActiveAtSave.set(TransactionSynchronizationManager.isActualTransactionActive());
            return inv.callRealMethod();
        }).when(nodeRepository).save(any());

        opsService.simulateTimeDrift("EDGE-02", 1500L);

        assertThat(txActiveAtSave.get())
                .as("直接调用 simulateTimeDrift 亦必须在激活的事务中执行")
                .isTrue();
    }

    @Test
    @DisplayName("直接调用 armFailure: 公共方法独立拥有 @Transactional 边界")
    void testDirectArmFailureActiveTransaction() {
        EdgePendingEvent event = new EdgePendingEvent();
        event.eventId = "EVT-TEST-002";
        event.edgeNodeId = "EDGE-03";
        event.eventType = "collision-risk";
        event.status = PendingEventStatuses.PENDING;
        event.edgeOccurredAt = OffsetDateTime.now();
        queueRepository.save(event);

        AtomicBoolean txActiveAtSave = new AtomicBoolean(false);
        doAnswer(inv -> {
            txActiveAtSave.set(TransactionSynchronizationManager.isActualTransactionActive());
            return inv.callRealMethod();
        }).when(queueRepository).save(any());

        opsService.armFailure("EVT-TEST-002");

        assertThat(txActiveAtSave.get())
                .as("直接调用 armFailure 亦必须在激活的事务中执行")
                .isTrue();
    }
}
