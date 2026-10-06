package com.bproject.safety.module.alert.demo;

import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Demo 告警维护组件（仅 Demo 场景使用）。
 *
 * <p>Phase B 运行边界收口：真实告警原则上不物理删除，{@code deleteById} 不得留在
 * {@link AlertRepository} 正式契约中（未来 openGauss 实现不能提供“按编号删除业务告警”）。
 * “风险突增演示”注入 / 回滚的 ALM-SURGE-* 数据通过本组件维护，调用方必须已经过
 * {@code DemoFeatureGuard.requireSimulator()} 门控。</p>
 *
 * <p>在 server profile 或非 InMemory 仓储环境下优雅降级为不支持（不阻断系统启动），
 * 业务 Service 不得直接依赖本类具体实现。</p>
 */
@Component
public class DemoAlertMaintenance {

    private final InMemoryAlertRepository repository;

    @Autowired
    public DemoAlertMaintenance(ObjectProvider<AlertRepository> repositoryProvider) {
        AlertRepository repo = repositoryProvider != null ? repositoryProvider.getIfAvailable() : null;
        if (repo instanceof InMemoryAlertRepository inMemory) {
            this.repository = inMemory;
        } else {
            this.repository = null;
        }
    }

    public DemoAlertMaintenance(@Nullable AlertRepository repository) {
        if (repository instanceof InMemoryAlertRepository inMemory) {
            this.repository = inMemory;
        } else {
            this.repository = null;
        }
    }

    /** 当前环境是否支持 Demo 告警维护（仅 InMemoryAlertRepository 可用）。 */
    public boolean isSupported() {
        return repository != null;
    }

    /** 保存一条 Demo 告警（委托正式仓储的 copy-on-write）。 */
    public DemoAlert save(DemoAlert alert) {
        if (repository == null) {
            throw new IllegalStateException("DemoAlertMaintenance 仅支持 InMemory Demo 存储");
        }
        return repository.save(alert);
    }

    /** 删除指定编号的 Demo 告警，返回是否删除成功。 */
    public boolean deleteById(String id) {
        if (repository == null) {
            return false;
        }
        return repository.deleteDemoAlert(id);
    }

    /** 删除所有编号以指定前缀开头的 Demo 告警，返回删除条数。 */
    public int deleteByIdPrefix(String prefix) {
        if (repository == null) {
            return 0;
        }
        List<DemoAlert> matches = repository.findAll().stream()
                .filter(a -> a.id != null && a.id.startsWith(prefix))
                .toList();
        int removed = 0;
        for (DemoAlert a : matches) {
            if (repository.deleteDemoAlert(a.id)) {
                removed++;
            }
        }
        return removed;
    }
}
