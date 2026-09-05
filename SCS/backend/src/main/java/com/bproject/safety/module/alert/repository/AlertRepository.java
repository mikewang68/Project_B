package com.bproject.safety.module.alert.repository;

import com.bproject.safety.module.alert.model.DemoAlert;
import java.util.List;
import java.util.Optional;

/**
 * 告警存储抽象。本阶段本地实现为 {@link InMemoryAlertRepository}（无需数据库即可运行）；
 * 后续服务器联调时新增 OpenGaussAlertRepository 即可替换，Service 不感知存储细节。
 */
public interface AlertRepository {

    /** 全量数据（按发生时间倒序），供指标聚合使用。 */
    List<DemoAlert> findAll();

    /** 条件筛选（不分页，分页由 {@link #page(AlertQuery)} 完成）。 */
    List<DemoAlert> filter(AlertQuery query);

    /** 条件筛选 + 分页。 */
    AlertPageResult page(AlertQuery query);

    Optional<DemoAlert> findById(String id);

    /** 新增或整体更新。 */
    DemoAlert save(DemoAlert alert);

    /** 按编号删除（供统计分析“风险突增演示”恢复注入的 demoScenario 事件；服务器实现替换为 openGauss 后保留）。 */
    boolean deleteById(String id);

    long count();

    /** 分页结果。 */
    record AlertPageResult(int page, int pageSize, long total, List<DemoAlert> list) {
    }
}
