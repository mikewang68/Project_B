package com.bproject.safety.support.phaseb;

import static org.assertj.core.api.Assertions.assertThat;

import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.collision.repository.CollisionRepository;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.ops.repository.EdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.EdgeNodeRepository;
import com.bproject.safety.module.ops.repository.OpsEventLogRepository;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
import com.bproject.safety.module.rule.repository.RuleRepository;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase B 仓储契约形状测试（编译期之外的反射兜底）：
 * 正式 Repository 接口不得暴露 Demo 维护方法（reset / clear / deleteById / findMutable），
 * 也不得泄露任何 java.util.concurrent 容器类型。未来 Jdbc*Repository 实现这些接口时，
 * 天然不会被要求“清空生产库 / 返回可变内部引用”。
 */
class RepositoryContractShapeTest {

    private static final List<Class<?>> REPOSITORIES = List.of(
            AlertRepository.class,
            AiEventRepository.class,
            PersonnelRepository.class,
            FenceRepository.class,
            CollisionRepository.class,
            RuleRepository.class,
            EdgeNodeRepository.class,
            EdgeEventQueueRepository.class,
            OpsEventLogRepository.class);

    private static final Set<String> FORBIDDEN_METHODS =
            Set.of("reset", "clear", "deleteById", "delete", "findMutable", "saveAll");

    @Test
    @DisplayName("9 个正式 Repository 接口均不暴露 reset/clear/delete/findMutable")
    void noDemoMaintenanceMethodsOnRepositoryContracts() {
        for (Class<?> repo : REPOSITORIES) {
            for (Method m : repo.getMethods()) {
                assertThat(FORBIDDEN_METHODS).as("%s#%s 不得是正式契约", repo.getSimpleName(), m.getName())
                        .doesNotContain(m.getName());
            }
        }
    }

    @Test
    @DisplayName("正式 Repository 接口不泄露 ConcurrentHashMap / CopyOnWriteArrayList 等实现类型")
    void noInfrastructureTypesLeakInSignatures() {
        for (Class<?> repo : REPOSITORIES) {
            for (Method m : repo.getMethods()) {
                for (Class<?> type : m.getParameterTypes()) {
                    assertThat(type.getName())
                            .as("%s#%s 参数", repo.getSimpleName(), m.getName())
                            .doesNotContain("java.util.concurrent");
                }
            }
        }
    }

    @Test
    @DisplayName("OpsEventLogRepository 是 append-only 契约：只有 append 与 recent")
    void opsLogIsAppendOnly() {
        Set<String> names = java.util.Arrays.stream(OpsEventLogRepository.class.getMethods())
                .filter(m -> m.getDeclaringClass() == OpsEventLogRepository.class)
                .map(Method::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder("append", "recent");
    }
}
