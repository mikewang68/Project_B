package com.bproject.safety.module.ai.repository;

import com.bproject.safety.module.ai.model.AiModelInfo;
import com.bproject.safety.support.demo.DemoClearableStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * 进程内 AI 模型存储与初始化种子仓库（遵守软删除与 copy-on-read/write 纯洁契约）。
 * server profile 下由 {@link JdbcAiModelRepository} 接管（ai_model 表持久化，
 * 空表自动补种同一份 {@link AiModelSeedData} 预置清单）。
 */
@Repository
@Profile("!server")
public class InMemoryAiModelRepository implements AiModelRepository, DemoClearableStore {

    private final ConcurrentHashMap<String, AiModelInfo> store = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public InMemoryAiModelRepository(Clock clock) {
        this.clock = clock;
        initDefaultSeeds();
    }

    public InMemoryAiModelRepository() {
        this(Clock.systemDefaultZone());
    }

    private void initDefaultSeeds() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (AiModelInfo preset : AiModelSeedData.presets(now)) {
            store.put(preset.id, preset);
        }
    }

    @Override
    public List<AiModelInfo> findAll() {
        return store.values().stream()
                .filter(m -> !m.isDeleted)
                .sorted(Comparator.comparing((AiModelInfo m) -> !m.isSelected)
                        .thenComparing(m -> m.id))
                .map(AiModelInfo::copy)
                .toList();
    }

    @Override
    public Optional<AiModelInfo> findById(String id) {
        AiModelInfo m = store.get(id);
        if (m == null || m.isDeleted) {
            return Optional.empty();
        }
        return Optional.of(m.copy());
    }

    @Override
    public Optional<AiModelInfo> findSelected() {
        return store.values().stream()
                .filter(m -> !m.isDeleted && m.isSelected)
                .findFirst()
                .map(AiModelInfo::copy);
    }

    @Override
    public AiModelInfo save(AiModelInfo model) {
        if (model == null || model.id == null) {
            throw new IllegalArgumentException("model and model.id must not be null");
        }
        if (model.updatedAt == null) {
            model.updatedAt = OffsetDateTime.now(clock);
        }
        store.put(model.id, model.copy());
        return model.copy();
    }

    @Override
    public boolean deleteById(String id) {
        AiModelInfo m = store.get(id);
        if (m == null || m.isDeleted) {
            return false;
        }
        m.isDeleted = true;
        m.updatedAt = OffsetDateTime.now(clock);
        return true;
    }

    @Override
    public void clearDemoData() {
        store.clear();
        initDefaultSeeds();
    }
}
