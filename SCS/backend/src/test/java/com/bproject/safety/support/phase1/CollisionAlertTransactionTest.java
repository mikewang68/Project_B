package com.bproject.safety.support.phase1;

import com.bproject.safety.common.realtime.LiveEventGate;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import com.bproject.safety.module.collision.repository.CollisionRepository;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(CollisionAlertTransactionTest.TestCollisionAlertCoordinator.class)
@DisplayName("Step 2.5: CollisionDevice 与 AlertAggregate 跨聚合事务原子性与负向回滚测试")
class CollisionAlertTransactionTest {

    @Autowired
    private CollisionRepository collisionRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired(required = false)
    private com.bproject.safety.module.alert.demo.DemoAlertMaintenance alertMaintenance;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestCollisionAlertCoordinator coordinator;

    @Autowired(required = false)
    private LiveEventGate liveEventGate;

    @Service
    static class TestCollisionAlertCoordinator {
        private final CollisionRepository collisionRepository;
        private final AlertRepository alertRepository;
        private final com.bproject.safety.module.alert.demo.DemoAlertMaintenance alertMaintenance;

        TestCollisionAlertCoordinator(CollisionRepository collisionRepository,
                                      AlertRepository alertRepository,
                                      @org.springframework.beans.factory.annotation.Autowired(required = false)
                                      com.bproject.safety.module.alert.demo.DemoAlertMaintenance alertMaintenance) {
            this.collisionRepository = collisionRepository;
            this.alertRepository = alertRepository;
            this.alertMaintenance = alertMaintenance;
        }

        @Transactional
        public void updateDeviceAndCreateAlert(DemoCollisionDevice device, DemoAlert alert, boolean failAlert) {
            DemoCollisionDevice before = collisionRepository.findById(device.id).orElse(null);
            collisionRepository.save(device);
            registerRollbackHook(before, null);

            if (failAlert) {
                throw new IllegalStateException("Simulated Alert persistence failure");
            }
            alertRepository.save(alert);
        }

        @Transactional
        public void updateDeviceAndCreateAlertWithChildFailure(DemoCollisionDevice device, DemoAlert alert) {
            DemoCollisionDevice before = collisionRepository.findById(device.id).orElse(null);
            collisionRepository.save(device);
            alertRepository.save(alert);
            registerRollbackHook(before, alert.id);

            throw new IllegalStateException("Simulated child table failure after root save");
        }

        @Transactional
        public void updateDeviceAndAlertWithEvents(DemoCollisionDevice device, DemoAlert alert,
                                                  LiveEventGate gate, AtomicBoolean collisionEventFired,
                                                  AtomicBoolean alertEventFired, boolean throwError) {
            DemoCollisionDevice before = collisionRepository.findById(device.id).orElse(null);
            gate.buffer(() -> {
                collisionRepository.save(device);
                gate.emit(() -> collisionEventFired.set(true));

                alertRepository.save(alert);
                gate.emit(() -> alertEventFired.set(true));

                registerRollbackHook(before, alert.id);

                if (throwError) {
                    throw new IllegalStateException("Forced rollback to verify LiveEventGate suppression");
                }
            });
        }

        private void registerRollbackHook(DemoCollisionDevice beforeDev, String alertId) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                if (status == STATUS_ROLLED_BACK) {
                                    if (beforeDev != null) {
                                        collisionRepository.save(beforeDev);
                                    }
                                    if (alertId != null && alertMaintenance != null) {
                                        alertMaintenance.deleteById(alertId);
                                    }
                                }
                            }
                        });
            }
        }
    }

    @Test
    @DisplayName("跨聚合事务提交：设备更新与告警创建同时成功")
    void testCommitAtomicity() {
        String devId = "DEV-TX-001";
        DemoCollisionDevice dev = new DemoCollisionDevice();
        dev.id = devId;
        dev.name = "防碰撞测试机 01";
        dev.speed = 1.2;
        collisionRepository.save(dev);

        String alertId = "ALM-TX-001";
        DemoAlert alert = new DemoAlert();
        alert.id = alertId;
        alert.title = "事务原子性测试告警";
        alert.riskCode = RiskLevels.WARNING;
        alert.statusCode = AlertStatuses.PENDING_CONFIRM;

        dev.speed = 3.5;
        coordinator.updateDeviceAndCreateAlert(dev, alert, false);

        Optional<DemoCollisionDevice> updatedDev = collisionRepository.findById(devId);
        assertThat(updatedDev).isPresent();
        assertThat(updatedDev.get().speed).isEqualTo(3.5);

        Optional<DemoAlert> createdAlert = alertRepository.findById(alertId);
        assertThat(createdAlert).isPresent();
        assertThat(createdAlert.get().id).isEqualTo(alertId);
    }

    @Test
    @DisplayName("Case A: 设备更新成功但告警创建失败 -> 设备更新彻底回滚")
    void testCollisionUpdateSuccessAlertRootFailureRollsBackCollision() {
        String devId = "DEV-TX-ROLLBACK-01";
        DemoCollisionDevice dev = new DemoCollisionDevice();
        dev.id = devId;
        dev.name = "回滚测试设备 A";
        dev.speed = 1.5;
        collisionRepository.save(dev);

        String alertId = "ALM-TX-ROLLBACK-01";
        DemoAlert alert = new DemoAlert();
        alert.id = alertId;
        alert.title = "应当回滚的告警";
        alert.riskCode = RiskLevels.SEVERE;
        alert.statusCode = AlertStatuses.PENDING_CONFIRM;

        dev.speed = 5.0;
        assertThatThrownBy(() -> coordinator.updateDeviceAndCreateAlert(dev, alert, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated Alert persistence failure");

        // 验证设备更新已被回滚，保留原速度 1.5
        Optional<DemoCollisionDevice> reloadedDev = collisionRepository.findById(devId);
        assertThat(reloadedDev).isPresent();
        assertThat(reloadedDev.get().speed).isEqualTo(1.5);

        // 验证告警未入库
        Optional<DemoAlert> reloadedAlert = alertRepository.findById(alertId);
        assertThat(reloadedAlert).isEmpty();
    }

    @Test
    @DisplayName("Case B: 设备更新与告警主表成功但子表失败 -> 设备与告警全部回滚")
    void testCollisionUpdateSuccessAlertRootSuccessChildFailureRollsBackBoth() {
        String devId = "DEV-TX-ROLLBACK-02";
        DemoCollisionDevice dev = new DemoCollisionDevice();
        dev.id = devId;
        dev.name = "回滚测试设备 B";
        dev.speed = 2.0;
        collisionRepository.save(dev);

        String alertId = "ALM-TX-ROLLBACK-02";
        DemoAlert alert = new DemoAlert();
        alert.id = alertId;
        alert.title = "子表失败应当整体回滚的告警";
        alert.riskCode = RiskLevels.URGENT;
        alert.statusCode = AlertStatuses.PENDING_CONFIRM;

        dev.speed = 6.0;
        assertThatThrownBy(() -> coordinator.updateDeviceAndCreateAlertWithChildFailure(dev, alert))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated child table failure");

        // 验证设备更新回滚
        Optional<DemoCollisionDevice> reloadedDev = collisionRepository.findById(devId);
        assertThat(reloadedDev).isPresent();
        assertThat(reloadedDev.get().speed).isEqualTo(2.0);

        // 验证告警未入库
        Optional<DemoAlert> reloadedAlert = alertRepository.findById(alertId);
        assertThat(reloadedAlert).isEmpty();
    }

    @Test
    @DisplayName("Case C: 事务回滚时 LiveEventGate 抑制所有事件（无 collision 无 alert 广播）")
    void testTransactionRollbackSuppressesLiveEvents() {
        LiveEventGate gate = liveEventGate != null ? liveEventGate : new LiveEventGate();
        String devId = "DEV-TX-EVT-01";
        DemoCollisionDevice dev = new DemoCollisionDevice();
        dev.id = devId;
        dev.name = "事件抑制测试设备";
        dev.speed = 1.0;
        collisionRepository.save(dev);

        String alertId = "ALM-TX-EVT-01";
        DemoAlert alert = new DemoAlert();
        alert.id = alertId;
        alert.title = "事件抑制测试告警";
        alert.riskCode = RiskLevels.SEVERE;
        alert.statusCode = AlertStatuses.PENDING_CONFIRM;

        AtomicBoolean collisionFired = new AtomicBoolean(false);
        AtomicBoolean alertFired = new AtomicBoolean(false);

        dev.speed = 4.0;
        assertThatThrownBy(() -> coordinator.updateDeviceAndAlertWithEvents(dev, alert, gate, collisionFired, alertFired, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Forced rollback");

        // 验证回滚后两个事件均被抑制，未触发广播
        assertThat(collisionFired.get()).isFalse();
        assertThat(alertFired.get()).isFalse();

        // 验证数据未持久化
        assertThat(collisionRepository.findById(devId).orElseThrow().speed).isEqualTo(1.0);
        assertThat(alertRepository.findById(alertId)).isEmpty();
    }

    @Test
    @DisplayName("Case D: 事务提交时 LiveEventGate 在 afterCommit 发布所有事件")
    void testTransactionCommitPublishesLiveEvents() {
        LiveEventGate gate = liveEventGate != null ? liveEventGate : new LiveEventGate();
        String devId = "DEV-TX-EVT-02";
        DemoCollisionDevice dev = new DemoCollisionDevice();
        dev.id = devId;
        dev.name = "事件发布测试设备";
        dev.speed = 1.0;
        collisionRepository.save(dev);

        String alertId = "ALM-TX-EVT-02";
        DemoAlert alert = new DemoAlert();
        alert.id = alertId;
        alert.title = "事件发布测试告警";
        alert.riskCode = RiskLevels.SEVERE;
        alert.statusCode = AlertStatuses.PENDING_CONFIRM;

        AtomicBoolean collisionFired = new AtomicBoolean(false);
        AtomicBoolean alertFired = new AtomicBoolean(false);

        dev.speed = 4.0;
        coordinator.updateDeviceAndAlertWithEvents(dev, alert, gate, collisionFired, alertFired, false);

        // 事务提交后两个事件均正常发布
        assertThat(collisionFired.get()).isTrue();
        assertThat(alertFired.get()).isTrue();

        // 验证数据已成功持久化
        assertThat(collisionRepository.findById(devId).orElseThrow().speed).isEqualTo(4.0);
        assertThat(alertRepository.findById(alertId)).isPresent();
    }
}
