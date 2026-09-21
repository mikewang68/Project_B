package com.bproject.safety.module.alert.demo;

import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Demo 告警维护组件（仅 Demo 场景使用）。
 *
 * <p>Phase B 运行边界收口：真实告警原则上不物理删除，{@code deleteById} 不得留在
 * {@link AlertRepository} 正式契约中（未来 openGauss 实现不能提供“按编号删除业务告警”）。
 * “风险突增演示”注入 / 回滚的 ALM-SURGE-* 数据通过本组件维护，调用方必须已经过
 * {@code DemoFeatureGuard.requireSimulator()} 门控。</p>
 *
 * <p>本组件允许依赖 InMemory 具体实现；业务 Service 不得注入本类。</p>
 */
@Component
public class DemoAlertMaintenance {

    private final InMemoryAlertRepository repository;

    public DemoAlertMaintenance(AlertRepository repository) {
        // Demo 维护能力只存在于 InMemory 实现；未来 JDBC 阶段本类随 Demo profile 一起退役 / 改造。
        if (!(repository instanceof InMemoryAlertRepository inMemory)) {
            throw new IllegalStateException("DemoAlertMaintenance 仅支持 InMemory Demo 存储");
        }
        this.repository = inMemory;
    }

    /** 保存一条 Demo 告警（委托正式仓储的 copy-on-write）。 */
    public DemoAlert save(DemoAlert alert) {
        return repository.save(alert);
    }

    /** 删除指定编号的 Demo 告警，返回是否删除成功。 */
    public boolean deleteById(String id) {
        return repository.deleteDemoAlert(id);
    }

    /** 删除所有编号以指定前缀开头的 Demo 告警，返回删除条数。 */
    public int deleteByIdPrefix(String prefix) {
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
