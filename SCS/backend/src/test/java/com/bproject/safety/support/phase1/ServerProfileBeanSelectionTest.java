package com.bproject.safety.support.phase1;

import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.ai.repository.InMemoryAiEventRepository;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.collision.repository.CollisionRepository;
import com.bproject.safety.module.collision.repository.InMemoryCollisionRepository;
import com.bproject.safety.module.collision.repository.JdbcCollisionRepository;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.fence.repository.InMemoryFenceRepository;
import com.bproject.safety.module.ops.repository.EdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.EdgeNodeRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeNodeRepository;
import com.bproject.safety.module.ops.repository.InMemoryOpsEventLogRepository;
import com.bproject.safety.module.ops.repository.JdbcEdgeNodeRepository;
import com.bproject.safety.module.ops.repository.OpsEventLogRepository;
import com.bproject.safety.module.personnel.repository.InMemoryPersonnelRepository;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
import com.bproject.safety.module.rule.repository.InMemoryRuleRepository;
import com.bproject.safety.module.rule.repository.RuleRepository;
import com.bproject.safety.support.masterdata.DemoDeviceMasterData;
import com.bproject.safety.support.masterdata.DemoMasterData;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Step 1: server profile 下 2 个 JDBC / 7 个 InMemory Bean 选择性装配测试")
class ServerProfileBeanSelectionTest {

    @Configuration(proxyBeanMethods = false)
    static class MockJdbcConfig {
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
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    MockJdbcConfig.class,
                    // 2 个目标 JDBC 仓储
                    JdbcCollisionRepository.class,
                    JdbcEdgeNodeRepository.class,
                    // 9 个 InMemory 仓储
                    InMemoryCollisionRepository.class,
                    InMemoryEdgeNodeRepository.class,
                    InMemoryAlertRepository.class,
                    InMemoryAiEventRepository.class,
                    InMemoryFenceRepository.class,
                    InMemoryPersonnelRepository.class,
                    InMemoryRuleRepository.class,
                    InMemoryEdgeEventQueueRepository.class,
                    InMemoryOpsEventLogRepository.class
            );

    @Test
    @DisplayName("server profile：严格激活 2 个 JDBC Repository，其余 7 个保持 InMemory")
    void testServerProfileBeanSelection() {
        contextRunner
                .withPropertyValues("spring.profiles.active=server")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    // 1. CollisionRepository -> JdbcCollisionRepository
                    assertThat(context).hasSingleBean(CollisionRepository.class);
                    assertThat(context.getBean(CollisionRepository.class))
                            .isInstanceOf(JdbcCollisionRepository.class);
                    assertThat(context).doesNotHaveBean(InMemoryCollisionRepository.class);

                    // 2. EdgeNodeRepository -> JdbcEdgeNodeRepository
                    assertThat(context).hasSingleBean(EdgeNodeRepository.class);
                    assertThat(context.getBean(EdgeNodeRepository.class))
                            .isInstanceOf(JdbcEdgeNodeRepository.class);
                    assertThat(context).doesNotHaveBean(InMemoryEdgeNodeRepository.class);

                    // 3. 其余 7 个业务 Repository 保持 InMemory
                    assertThat(context).hasSingleBean(AlertRepository.class);
                    assertThat(context.getBean(AlertRepository.class)).isInstanceOf(InMemoryAlertRepository.class);

                    assertThat(context).hasSingleBean(AiEventRepository.class);
                    assertThat(context.getBean(AiEventRepository.class)).isInstanceOf(InMemoryAiEventRepository.class);

                    assertThat(context).hasSingleBean(FenceRepository.class);
                    assertThat(context.getBean(FenceRepository.class)).isInstanceOf(InMemoryFenceRepository.class);

                    assertThat(context).hasSingleBean(PersonnelRepository.class);
                    assertThat(context.getBean(PersonnelRepository.class)).isInstanceOf(InMemoryPersonnelRepository.class);

                    assertThat(context).hasSingleBean(RuleRepository.class);
                    assertThat(context.getBean(RuleRepository.class)).isInstanceOf(InMemoryRuleRepository.class);

                    assertThat(context).hasSingleBean(EdgeEventQueueRepository.class);
                    assertThat(context.getBean(EdgeEventQueueRepository.class)).isInstanceOf(InMemoryEdgeEventQueueRepository.class);

                    assertThat(context).hasSingleBean(OpsEventLogRepository.class);
                    assertThat(context.getBean(OpsEventLogRepository.class)).isInstanceOf(InMemoryOpsEventLogRepository.class);
                });
    }

    @Test
    @DisplayName("default / test profile（非 server）：全部 9 个业务 Repository 均使用 InMemory")
    void testDefaultProfileBeanSelection() {
        contextRunner
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context).hasSingleBean(CollisionRepository.class);
                    assertThat(context.getBean(CollisionRepository.class))
                            .isInstanceOf(InMemoryCollisionRepository.class);
                    assertThat(context).doesNotHaveBean(JdbcCollisionRepository.class);

                    assertThat(context).hasSingleBean(EdgeNodeRepository.class);
                    assertThat(context.getBean(EdgeNodeRepository.class))
                            .isInstanceOf(InMemoryEdgeNodeRepository.class);
                    assertThat(context).doesNotHaveBean(JdbcEdgeNodeRepository.class);
                });
    }
}
