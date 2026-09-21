package com.bproject.safety.support.phaseb;

import static org.assertj.core.api.Assertions.assertThat;

import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.collision.repository.CollisionRepository;
import com.bproject.safety.module.collision.repository.InMemoryCollisionRepository;
import com.bproject.safety.module.collision.service.CollisionService;
import com.bproject.safety.module.fence.repository.InMemoryFenceRepository;
import com.bproject.safety.module.personnel.repository.InMemoryPersonnelRepository;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase B.5 B5-02: Collision stale activeAlertId 与告警生命周期回归测试。
 * 验证：
 * 1. 第一次建单 -> 关闭告警 -> pair reset -> 第二次风险必须能成功创建新 Alert B (Alert B != Alert A)；
 * 2. 告警关闭但 pair 未 reset (stale activeAlertId 残留) 时，下一次 ensure 也能自动识别旧缓存失效并创建新告警。
 */
@SpringBootTest
@ActiveProfiles("test")
class CollisionAlertLifecycleTest {

    @Autowired
    private CollisionService collisionService;
    @Autowired
    private CollisionRepository collisionRepository;
    @Autowired
    private AlertRepository alertRepository;
    @Autowired
    private AlertService alertService;
    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        InMemoryAlertRepository alerts = (InMemoryAlertRepository) alertRepository;
        alerts.clearDemoData();
        AlertDemoSeeder.buildSeeds(clock).forEach(alertRepository::save);
        ((InMemoryCollisionRepository) collisionRepository).resetDemoData();
        collisionService.resetAllPairs();
    }

    private void approachTimes(int count) {
        for (int i = 0; i < count; i++) {
            collisionService.simulate("VEH-07", "approach");
        }
    }

    private List<DemoAlert> collisionAlerts() {
        return alertRepository.findAll().stream()
                .filter(a -> "设备防碰撞".equals(a.source) && a.dedupKey != null && a.dedupKey.contains("VEH-07"))
                .toList();
    }

    @Test
    @DisplayName("两轮碰撞告警：第一轮建单 -> 关闭告警 -> pair reset -> 第二轮靠近必须成功建单且 Alert B != Alert A")
    void twoRoundsOfCollisionApproachWithAlertCloseAndReset() {
        // 第一轮：接近 3 次达到严重风险 (5.4m) -> 创建 Alert A
        approachTimes(3);

        List<DemoAlert> firstAlerts = collisionAlerts().stream()
                .filter(a -> !AlertStatuses.CLOSED.equals(a.statusCode))
                .toList();
        assertThat(firstAlerts).hasSize(1);
        DemoAlert alertA = firstAlerts.get(0);

        // 关闭告警：模拟处置和复核关闭（或直接修改状态关闭）
        alertA.statusCode = AlertStatuses.CLOSED;
        alertRepository.save(alertA);

        // 复位场景 (resetPair)
        collisionService.simulate("VEH-07", "reset");

        // 第二轮：再次接近 3 次达到严重风险 (5.4m) -> 必须创建 Alert B
        approachTimes(3);

        List<DemoAlert> secondOpenAlerts = collisionAlerts().stream()
                .filter(a -> !AlertStatuses.CLOSED.equals(a.statusCode))
                .toList();
        assertThat(secondOpenAlerts).hasSize(1);
        DemoAlert alertB = secondOpenAlerts.get(0);

        assertThat(alertB.id)
                .as("第二轮生成的告警必须是全新的告警实例，不能因旧 activeAlertId 残留而不建单")
                .isNotEqualTo(alertA.id);
    }

    @Test
    @DisplayName("旧告警已关闭但 pair 未 reset 场景：ensure 必须自动识别旧缓存失效并创建新告警")
    void closedAlertWithoutResetStillCreatesNewAlert() {
        // 第一次接近建单
        approachTimes(3);

        DemoAlert openAlert = alertService.findOpenByDedupKey("COLLISION:VEH-07:TIP-02");
        assertThat(openAlert).isNotNull();
        String alertAId = openAlert.id;

        // 告警关闭
        openAlert.statusCode = AlertStatuses.CLOSED;
        alertRepository.save(openAlert);

        // 注意：此处刻意不调用 resetPair，让 pair.activeAlertId 依然指向 alertAId
        // 下一次再次触发严重/紧急风险接近：
        approachTimes(1);

        DemoAlert newOpenAlert = alertService.findOpenByDedupKey("COLLISION:VEH-07:TIP-02");
        assertThat(newOpenAlert)
                .as("旧告警已关闭时，即使 activeAlertId 未重置，也必须自动清空旧缓存并建立新告警")
                .isNotNull();
        assertThat(newOpenAlert.id).isNotEqualTo(alertAId);
    }
}
