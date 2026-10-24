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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * 进程内 AI 事件存储（Backend Demo 专用）：本地无需 openGauss 即可运行全部 AI 接口。
 *
 * <p>Phase B：copy-on-read / copy-on-write——find/save 均经过 {@link DemoAiEvent#copy()}；
 * 修改 find 返回对象但不 save 不会落库。清空仅通过 {@link DemoClearableStore} 供 Demo / Test 使用。</p>
 */
@Repository
@Profile("!server")
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
        return activeEvents().map(DemoAiEvent::copy).sorted(TIME_DESC).toList();
    }

    @Override
    public List<DemoAiEvent> filter(AiEventQuery q) {
        return activeEvents()
                .map(DemoAiEvent::copy)
                .filter(e -> matchKeyword(q.keyword(), e))
                .filter(e -> q.type() == null || q.type().equals(e.type))
                .filter(e -> q.area() == null || q.area().equals(e.area))
                .filter(e -> q.camera() == null || q.camera().equals(e.camera))
                .filter(e -> q.status() == null || (AiReviewStatuses.normalize(q.status()) != null
                        && AiReviewStatuses.normalize(q.status()).equals(e.statusCode)))
                .filter(e -> q.risk() == null || (AiRiskLevels.normalize(q.risk()) != null
                        && AiRiskLevels.normalize(q.risk()).equals(e.riskCode)))
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
        return activeEvents().count();
    }

    private java.util.stream.Stream<DemoAiEvent> activeEvents() {
        return store.values().stream().filter(e -> !"CANCELLED".equals(e.statusCode));
    }
    @Override public Counts counts() {
        return new Counts(activeEvents().count(),
            activeEvents().filter(e -> AiReviewStatuses.PENDING.equals(e.statusCode)||AiReviewStatuses.UNCERTAIN.equals(e.statusCode)).count(),
            activeEvents().filter(e -> AiReviewStatuses.isConfirmedChain(e.statusCode)).count(),
            activeEvents().filter(e -> AiReviewStatuses.FALSE_POSITIVE.equals(e.statusCode)).count(),
            activeEvents().filter(e -> e.health==null||!java.util.Set.of("正常","NORMAL","HEALTHY","OK").contains(e.health)).count());
    }
    @Override public long countOccurredBetween(OffsetDateTime fromInclusive,OffsetDateTime untilExclusive) {
        return activeEvents().filter(e -> e.occurredAt!=null&&!e.occurredAt.isBefore(fromInclusive)&&e.occurredAt.isBefore(untilExclusive)).count();
    }
    @Override public List<String> distinctAreas() {
        return activeEvents().map(e -> e.area).filter(v -> v!=null&&!v.isBlank()).distinct().sorted().toList();
    }
    @Override public List<String> distinctCameras() {
        return activeEvents().map(e -> e.camera).filter(v -> v!=null&&!v.isBlank()).distinct().sorted().toList();
    }

    @Override
    public boolean claimLinkedAlert(String eventId, String alertId) {
        DemoAiEvent e = store.get(eventId);
        if (e == null) {
            return false;
        }
        // 单 JVM 下 confirm 已由 KeyedLock 串行化，此处 CAS 为契约完备性
        if (e.linkedAlertId == null || e.linkedAlertId.isBlank()) {
            e.linkedAlertId = alertId;
            return true;
        }
        return e.linkedAlertId.equals(alertId);
    }

    @Override
    public List<DemoAiEvent> findByLinkedAlertId(String alertId) {
        if (alertId == null || alertId.isBlank()) {
            return List.of();
        }
        return store.values().stream()
                .filter(e -> alertId.equals(e.linkedAlertId))
                .map(DemoAiEvent::copy)
                .toList();
    }

    @Override
    public boolean claimLinkedAlert(String eventId, String alertId) {
        DemoAiEvent e = store.get(eventId);
        if (e == null) {
            return false;
        }
        // 单 JVM 下 confirm 已由 KeyedLock 串行化，此处 CAS 为契约完备性
        if (e.linkedAlertId == null || e.linkedAlertId.isBlank()) {
            e.linkedAlertId = alertId;
            return true;
        }
        return e.linkedAlertId.equals(alertId);
    }

    @Override
    public List<DemoAiEvent> findByLinkedAlertId(String alertId) {
        if (alertId == null || alertId.isBlank()) {
            return List.of();
        }
        return store.values().stream()
                .filter(e -> alertId.equals(e.linkedAlertId))
                .map(DemoAiEvent::copy)
                .toList();
    }

    @Override
    public long countPendingReview() {
        return store.values().stream()
                .filter(e -> com.bproject.safety.module.ai.model.AiReviewStatuses.PENDING.equals(e.statusCode)
                        || com.bproject.safety.module.ai.model.AiReviewStatuses.UNCERTAIN.equals(e.statusCode))
                .count();
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
