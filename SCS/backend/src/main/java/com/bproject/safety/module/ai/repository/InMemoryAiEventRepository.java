package com.bproject.safety.module.ai.repository;

import com.bproject.safety.module.ai.model.AiPageResult;
import com.bproject.safety.module.ai.model.AiReviewStatuses;
import com.bproject.safety.module.ai.model.AiRiskLevels;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.support.demo.DemoClearableStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * 进程内 AI 事件存储（Backend Demo 专用）：本地无需 openGauss 即可运行全部 AI 接口。
 *
 * <p>Phase B：copy-on-read / copy-on-write——find/save 均经过 {@link DemoAiEvent#copy()}；
 * 修改 find 返回对象但不 save 不会落库。清空仅通过 {@link DemoClearableStore} 供 Demo / Test 使用。</p>
 */
@Repository
public class InMemoryAiEventRepository implements AiEventRepository, DemoClearableStore {

    private final ConcurrentHashMap<String, DemoAiEvent> store = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public InMemoryAiEventRepository(Clock clock) {
        this.clock = clock;
    }

    /** 兼容无参构造（部分单元测试直接 new；使用系统时钟）。 */
    public InMemoryAiEventRepository() {
        this(Clock.systemDefaultZone());
    }

    private static final Comparator<DemoAiEvent> TIME_DESC = Comparator
            .comparing((DemoAiEvent e) -> e.occurredAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(e -> e.id, Comparator.nullsLast(Comparator.reverseOrder()));

    @Override
    public List<DemoAiEvent> findAll() {
        return store.values().stream().map(DemoAiEvent::copy).sorted(TIME_DESC).toList();
    }

    @Override
    public List<DemoAiEvent> filter(AiEventQuery q) {
        return store.values().stream()
                .map(DemoAiEvent::copy)
                .filter(e -> matchKeyword(q.keyword(), e))
                .filter(e -> q.type() == null || q.type().equals(e.type))
                .filter(e -> q.area() == null || q.area().equals(e.area))
                .filter(e -> q.camera() == null || q.camera().equals(e.camera))
                .filter(e -> q.status() == null || AiReviewStatuses.normalize(q.status()).equals(e.statusCode))
                .filter(e -> q.risk() == null || AiRiskLevels.normalize(q.risk()).equals(e.riskCode))
                .filter(e -> matchConfidence(q.confidence(), e))
                .filter(e -> matchTimeBucket(q.timeBucket(), e))
                .sorted(TIME_DESC)
                .toList();
    }

    @Override
    public AiPageResult page(AiEventQuery q) {
        List<DemoAiEvent> all = filter(q);
        int from = Math.min((q.page() - 1) * q.pageSize(), all.size());
        int to = Math.min(from + q.pageSize(), all.size());
        return new AiPageResult(q.page(), q.pageSize(), all.size(), all.subList(from, to));
    }

    @Override
    public Optional<DemoAiEvent> findById(String id) {
        DemoAiEvent e = store.get(id);
        return e == null ? Optional.empty() : Optional.of(e.copy());
    }

    @Override
    public DemoAiEvent save(DemoAiEvent event) {
        DemoAiEvent persisted = event.copy();
        if (persisted.updatedAt == null) {
            persisted.updatedAt = OffsetDateTime.now(clock);
        }
        store.put(event.id, persisted);
        return persisted.copy();
    }

    @Override
    public long count() {
        return store.size();
    }

    /** 清空存储（DemoClearableStore，仅 Demo / 测试调用）。 */
    @Override
    public void clearDemoData() {
        store.clear();
    }

    private boolean matchKeyword(String kw, DemoAiEvent e) {
        if (kw == null) {
            return true;
        }
        return contains(e.id, kw) || contains(e.type, kw) || contains(e.camera, kw)
                || contains(e.area, kw) || contains(e.model, kw) || contains(e.relatedPerson, kw);
    }

    private boolean contains(String v, String kw) {
        return v != null && v.contains(kw);
    }

    /** 置信度档位：high≥85 / mid 70~84 / low<70（与前端筛选口径一致）。 */
    private boolean matchConfidence(String band, DemoAiEvent e) {
        if (band == null) {
            return true;
        }
        return switch (band) {
            case "high" -> e.confidence >= 85;
            case "mid" -> e.confidence >= 70 && e.confidence < 85;
            case "low" -> e.confidence < 70;
            default -> true;
        };
    }

    /**
     * 演示班次时间桶：按权威时间 {@code occurredAt}（上海时区本地时间）过滤，
     * 不再用展示用 HH:mm:ss 字符串做比较（F-18）。阈值对齐 Demo 种子班次。
     */
    private boolean matchTimeBucket(String bucket, DemoAiEvent e) {
        if (bucket == null || e.occurredAt == null) {
            return true;
        }
        java.time.LocalTime t = e.occurredAt.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalTime();
        java.time.LocalTime cut21 = java.time.LocalTime.of(21, 0);
        java.time.LocalTime cut22 = java.time.LocalTime.of(22, 0);
        return switch (bucket) {
            case "1h" -> !t.isBefore(cut22);
            case "2h" -> !t.isBefore(cut21) && t.isBefore(cut22);
            case "earlier" -> t.isBefore(cut21);
            default -> true;
        };
    }
}
