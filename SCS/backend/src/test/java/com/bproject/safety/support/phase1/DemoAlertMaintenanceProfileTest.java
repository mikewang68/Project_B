package com.bproject.safety.support.phase1;

import com.bproject.safety.module.alert.demo.DemoAlertMaintenance;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("Step 1.5: DemoAlertMaintenance 解耦与运行边界测试（P1-1）")
class DemoAlertMaintenanceProfileTest {

    @Test
    @DisplayName("InMemory 仓储环境：isSupported 为 true，保存与前缀删除功能正常")
    void testInMemoryRepositorySupported() {
        InMemoryAlertRepository inMemoryRepo = new InMemoryAlertRepository(Clock.systemDefaultZone());
        DemoAlertMaintenance maintenance = new DemoAlertMaintenance(inMemoryRepo);

        assertThat(maintenance.isSupported()).isTrue();

        DemoAlert alert = new DemoAlert();
        alert.id = "ALM-SURGE-01";
        alert.title = "测试突增告警";
        alert.riskCode = "HIGH";
        maintenance.save(alert);

        assertThat(inMemoryRepo.findById("ALM-SURGE-01")).isPresent();

        int deleted = maintenance.deleteByIdPrefix("ALM-SURGE-");
        assertThat(deleted).isEqualTo(1);
        assertThat(inMemoryRepo.findById("ALM-SURGE-01")).isEmpty();
    }

    @Test
    @DisplayName("非 InMemory 仓储（如未来 JDBC 仓储或 Mock）：构造不抛异常，isSupported 为 false，优雅降级")
    void testNonInMemoryRepositoryGracefulDegradation() {
        AlertRepository mockJdbcRepo = mock(AlertRepository.class);
        DemoAlertMaintenance maintenance = new DemoAlertMaintenance(mockJdbcRepo);

        assertThat(maintenance.isSupported()).isFalse();

        // deleteById 返回 false 而不抛异常
        assertThat(maintenance.deleteById("ALM-SURGE-01")).isFalse();

        // deleteByIdPrefix 返回 0 而不抛异常
        assertThat(maintenance.deleteByIdPrefix("ALM-SURGE-")).isEqualTo(0);

        // 尝试 save 时抛出明确语义的 IllegalStateException
        DemoAlert alert = new DemoAlert();
        alert.id = "ALM-SURGE-01";
        assertThatThrownBy(() -> maintenance.save(alert))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仅支持 InMemory Demo 存储");
    }

    @Test
    @DisplayName("Spring ObjectProvider 注入非 InMemory 仓储时正常实例化，不阻断上下文启动")
    void testObjectProviderResolutionWithNonInMemory() {
        AlertRepository nonInMemory = mock(AlertRepository.class);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("alertRepository", nonInMemory);

        DemoAlertMaintenance maintenance = new DemoAlertMaintenance(beanFactory.getBeanProvider(AlertRepository.class));
        assertThat(maintenance.isSupported()).isFalse();
    }

    @Test
    @DisplayName("Spring ObjectProvider 在无任何 AlertRepository Bean 时亦安全降级")
    void testObjectProviderResolutionWithNoBean() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        DemoAlertMaintenance maintenance = new DemoAlertMaintenance(beanFactory.getBeanProvider(AlertRepository.class));
        assertThat(maintenance.isSupported()).isFalse();
    }
}
