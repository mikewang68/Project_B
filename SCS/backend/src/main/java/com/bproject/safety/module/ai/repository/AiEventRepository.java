package com.bproject.safety.module.ai.repository;

import com.bproject.safety.module.ai.model.AiPageResult;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import java.util.List;
import java.util.Optional;

/**
 * AI 事件存储抽象。本地实现为 {@link InMemoryAiEventRepository}（无需数据库即可运行）；
 * 后续服务器联调时新增 openGauss 实现即可替换，Service 不感知存储细节。
 *
 * <p>Phase B：find/save 均为 detached 副本，所有持久化变更必须显式 {@link #save(DemoAiEvent)}；
 * 清空仅通过 {@code DemoClearableStore} 供 Demo / Test 使用。</p>
 */
public interface AiEventRepository {

    /** 全量数据（按发生时间倒序），供指标聚合使用。 */
    List<DemoAiEvent> findAll();

    /** 条件筛选（不分页）。 */
    List<DemoAiEvent> filter(AiEventQuery query);

    /** 条件筛选 + 分页。 */
    AiPageResult page(AiEventQuery query);

    Optional<DemoAiEvent> findById(String id);

    /** 新增或整体更新（聚合快照，由实现负责 copy-on-write）。 */
    DemoAiEvent save(DemoAiEvent event);

    long count();

    long countPendingReview();

    /**
     * 复查-D4：关联告警原子抢占（CAS 语义）——仅当 linked_alert_id 为空或等于目标值时写入。
     * 多实例并发确认同一 AI 事件时，只有一个告警能抢占成功，其余侧收到 false 并把自己的
     * 重复告警转为 CANCELLED，杜绝双建。
     */
    boolean claimLinkedAlert(String eventId, String alertId);

    /** 复查-D6：按关联告警反查 AI 事件（关闭镜像同步用），通常 0~1 条。 */
    List<DemoAiEvent> findByLinkedAlertId(String alertId);
}
