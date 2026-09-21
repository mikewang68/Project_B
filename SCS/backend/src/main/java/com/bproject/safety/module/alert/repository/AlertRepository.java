package com.bproject.safety.module.alert.repository;

import com.bproject.safety.module.alert.model.AlertPageResult;
import com.bproject.safety.module.alert.model.DemoAlert;
import java.util.List;
import java.util.Optional;

/**
 * 告警存储抽象。本阶段本地实现为 {@link InMemoryAlertRepository}（无需数据库即可运行）；
 * 后续服务器联调时新增 OpenGaussAlertRepository 即可替换，Service 不感知存储细节。
 *
 * <p>Phase B：契约 persistence-neutral——不暴露 clear / deleteById（真实告警原则上不物理删除，
 * Demo 突增数据回滚走 {@code DemoAlertMaintenance}）；find/save 均为 detached 副本，
 * 所有持久化变更必须显式 {@link #save(DemoAlert)}。</p>
 */
public interface AlertRepository {

    /** 全量数据（按发生时间倒序），供指标聚合使用。 */
    List<DemoAlert> findAll();

    /** 条件筛选（不分页，分页由 {@link #page(AlertQuery)} 完成）。 */
    List<DemoAlert> filter(AlertQuery query);

    /** 条件筛选 + 分页。 */
    AlertPageResult page(AlertQuery query);

    Optional<DemoAlert> findById(String id);

    /**
     * 按去重键查找未关闭告警（未来 SQL：WHERE dedup_key=? AND status_code &lt;&gt; 'CLOSED'）。
     * 供风险事件去重 / 升级复用同一 Alert，替代 Service 层 findAll 内存过滤。
     */
    Optional<DemoAlert> findOpenByDedupKey(String dedupKey);

    /** 新增或整体更新（聚合快照，由实现负责 copy-on-write）。 */
    DemoAlert save(DemoAlert alert);

    long count();
}
