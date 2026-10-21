package com.bproject.safety.module.ai.repository;

import com.bproject.safety.module.ai.model.AiModelInfo;
import java.util.List;
import java.util.Optional;

/**
 * AI 模型仓库契约接口。
 */
public interface AiModelRepository {

    /** 查询所有有效模型（自动过滤 is_deleted） */
    List<AiModelInfo> findAll();

    /** 根据 ID 查询模型 */
    Optional<AiModelInfo> findById(String id);

    /** 查询当前选用的激活模型（若无则取首个可用模型） */
    Optional<AiModelInfo> findSelected();

    /** 保存或更新模型 */
    AiModelInfo save(AiModelInfo model);

    /** 软删除模型 */
    boolean deleteById(String id);
}
