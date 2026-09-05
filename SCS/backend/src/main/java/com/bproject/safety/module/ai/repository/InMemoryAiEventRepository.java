package com.bproject.safety.module.ai.repository;

import com.bproject.safety.module.ai.model.DemoAiEvent;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 进程内 AI 事件存储（Backend Demo 专用）：本地无需 openGauss 即可运行全部 AI 接口。
 * 查询统一返回副本，避免外部直接改写存储；写操作在 Service 层对单条事件加对象锁。
 */
@Repository
public class InMemoryAiEventRepository implements AiEventRepository {

    private final ConcurrentHashMap<String, DemoAiEvent> store = new ConcurrentHashMap<>();

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
                .filter(e -> q.status() == null || q.status().equals(e.status))
                .filter(e -> q.risk() == null || q.risk().equals(e.risk))
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
        if (event.updatedAt == null) {
            event.updatedAt = OffsetDateTime.now();
        }
        store.put(event.id, event);
        return event.copy();
    }

    @Override
    public long count() {
        return store.size();
    }

    /** 清空存储（测试重置；openGauss 实现替换后移除）。 */
    public void clear() {
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

    /** 演示班次时间桶（按展示用 HH:mm:ss 过滤，与前端旧逻辑一致）。 */
    private boolean matchTimeBucket(String bucket, DemoAiEvent e) {
        if (bucket == null || e.time == null) {
            return true;
        }
        return switch (bucket) {
            case "1h" -> e.time.compareTo("22:00:00") >= 0;
            case "2h" -> e.time.compareTo("21:00:00") >= 0 && e.time.compareTo("22:00:00") < 0;
            case "earlier" -> e.time.compareTo("21:00:00") < 0;
            default -> true;
        };
    }
}
