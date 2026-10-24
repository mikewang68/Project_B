package com.bproject.safety.module.alert.repository;

import com.bproject.safety.module.alert.model.AlertPageResult;
import com.bproject.safety.module.alert.model.DemoAlert;
import java.util.List;
import java.util.Map;
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

    /**
     * 按状态码计数（Step 8 / P0-4：大屏与统计的态势计数口径改为存储侧 GROUP BY 聚合，
     * 消除每次刷新多次全量聚合加载）。键为状态机 code，值为其告警数。
     */
    Map<String, Long> countByStatus();

    /** 未关闭（status &lt;&gt; CLOSED）告警按风险等级计数。 */
    Map<String, Long> countOpenByRisk();

    /** 按事件类型计数（eventType 为空时回退标题，与 riskDistribution 口径一致）。 */
    Map<String, Long> countByEventType();

    /**
     * 作用域聚合摘要（压测打磨）：KPI / 等级分布 / 区域排行 / 重复事件 / 平均响应与关闭时长
     * 的一次性 SQL 聚合（JDBC），语义与对该 scope 全量行做内存聚合一致。
     * 供统计分析等只读聚合路径使用，替代全窗口整表加载。
     */
    AlertScopeSummary summarize(AlertQuery query);

    /**
     * 复查②：按小时桶聚合近 {@code hours} 小时告警（label="HH:00"，Asia/Shanghai）。
     * 仅供态势趋势曲线；空桶由调用方补齐。
     */
    java.util.List<AlertTrendBucket> trendByHour(int hours);

    /** 复查②：按日桶聚合近 {@code days} 天告警（label="MM/dd"）。空桶由调用方补齐。 */
    java.util.List<AlertTrendBucket> trendByDay(int days);

    /**
     * 有界最近行：按发生时间倒序取至多 limit 条，行内容与 {@link #filter(AlertQuery)} 等价
     * （含聚合子数据）。供分组榜单（设备 / 人员 / 班组 / 趋势）等只需有限样本的路径使用；
     * 作用域 ≤limit 时与全量 filter 结果一致。缺省实现回退 filter 后截断。
     */
    default List<DemoAlert> filterRecent(AlertQuery query, int limit) {
        List<DemoAlert> all = filter(query);
        return all.size() <= limit ? all : all.subList(0, limit);
    }
}
