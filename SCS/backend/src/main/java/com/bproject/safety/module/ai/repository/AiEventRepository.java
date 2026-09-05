package com.bproject.safety.module.ai.repository;

import com.bproject.safety.module.ai.model.DemoAiEvent;
import java.util.List;
import java.util.Optional;

/**
 * AI 事件存储抽象。本地实现为 {@link InMemoryAiEventRepository}（无需数据库即可运行）；
 * 后续服务器联调时新增 openGauss 实现即可替换，Service 不感知存储细节。
 */
public interface AiEventRepository {

    /** 全量数据（按发生时间倒序），供指标聚合使用。 */
    List<DemoAiEvent> findAll();

    /** 条件筛选（不分页）。 */
    List<DemoAiEvent> filter(AiEventQuery query);

    /** 条件筛选 + 分页。 */
    AiPageResult page(AiEventQuery query);

    Optional<DemoAiEvent> findById(String id);

    DemoAiEvent save(DemoAiEvent event);

    long count();

    record AiPageResult(int page, int pageSize, long total, List<DemoAiEvent> list) {
    }
}
