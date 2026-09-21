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
}
