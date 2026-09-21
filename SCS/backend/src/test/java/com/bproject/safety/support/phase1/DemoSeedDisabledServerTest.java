package com.bproject.safety.support.phase1;

import com.bproject.safety.module.collision.repository.JdbcCollisionRepository;
import com.bproject.safety.module.fence.repository.InMemoryFenceRepository;
import com.bproject.safety.module.ops.repository.JdbcEdgeNodeRepository;
import com.bproject.safety.module.ops.service.OpsInventory;
import com.bproject.safety.module.personnel.repository.InMemoryPersonnelRepository;
import com.bproject.safety.support.demo.DemoFeatureGuard;
import com.bproject.safety.support.demo.DemoSeedInitializer;
import com.bproject.safety.support.masterdata.DemoDeviceMasterData;
import com.bproject.safety.support.masterdata.DemoMasterData;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Step 1: server profile 下 Demo 种子关闭（app.demo.seed-enabled=false）启动解耦测试")
class DemoSeedDisabledServerTest {

    @Configuration(proxyBeanMethods = false)
    static class ServerContextConfig {
        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
            return mock(NamedParameterJdbcTemplate.class);
        }

        @Bean
        Clock testClock() {
            return Clock.systemDefaultZone();
        }

        @Bean
        DemoMasterData demoMasterData() {
            return new DemoMasterData();
        }

        @Bean
        DemoDeviceMasterData demoDeviceMasterData(DemoMasterData masterData) {
            return new DemoDeviceMasterData(masterData);
        }

        @Bean
        DemoFeatureGuard demoFeatureGuard() {
            return new DemoFeatureGuard(false, false);
        }

        @Bean
        InMemoryFenceRepository inMemoryFenceRepository() {
            return new InMemoryFenceRepository();
        }

        @Bean
        InMemoryPersonnelRepository inMemoryPersonnelRepository() {
            return new InMemoryPersonnelRepository();
        }

        @Bean
        OpsInventory opsInventory(Clock clock, DemoDeviceMasterData deviceMasterData) {
            return new OpsInventory(clock, deviceMasterData);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ServerContextConfig.class,
                    JdbcCollisionRepository.class,
                    JdbcEdgeNodeRepository.class,
                    DemoSeedInitializer.class
            )
            .withPropertyValues(
                    "spring.profiles.active=server",
                    "app.demo.seed-enabled=false",
                    "app.demo.simulator-enabled=false"
            );

    @Test
    @DisplayName("server profile 下缺少 InMemoryCollision/EdgeNode Bean 时，DemoSeedInitializer 依然平稳启动且不报错")
    void testServerContextStartupWithoutInMemoryBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context).hasSingleBean(DemoSeedInitializer.class);
            DemoSeedInitializer initializer = context.getBean(DemoSeedInitializer.class);
            assertThat(initializer).isNotNull();

            // 执行 run 方法，验证 seed-enabled=false 时安全跳过
            initializer.run();

            // 确认 JDBC 仓库已作为唯一 Bean 就绪
            assertThat(context).hasSingleBean(JdbcCollisionRepository.class);
            assertThat(context).hasSingleBean(JdbcEdgeNodeRepository.class);
        });
    }
}
