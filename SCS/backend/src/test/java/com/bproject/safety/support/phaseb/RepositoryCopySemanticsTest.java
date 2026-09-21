package com.bproject.safety.support.phaseb;

import static org.assertj.core.api.Assertions.assertThat;

import com.bproject.safety.module.ai.model.AiBox;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.ai.repository.InMemoryAiEventRepository;
import com.bproject.safety.module.ai.seed.AiDemoSeeder;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.TimelineEvent;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.module.collision.repository.CollisionRepository;
import com.bproject.safety.module.collision.repository.InMemoryCollisionRepository;
import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.FencePoint;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.fence.repository.InMemoryFenceRepository;
import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.EdgeLocalLinkage;
import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.model.PendingEventStatuses;
import com.bproject.safety.module.ops.repository.EdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.EdgeNodeRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeNodeRepository;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.personnel.repository.InMemoryPersonnelRepository;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
import com.bproject.safety.module.rule.model.DemoRule;
import com.bproject.safety.module.rule.repository.InMemoryRuleRepository;
import com.bproject.safety.module.rule.repository.RuleRepository;
import com.bproject.safety.module.rule.seed.RuleDemoSeeder;
import com.bproject.safety.support.demo.DemoFeatureGuard;
import com.bproject.safety.support.masterdata.DemoDeviceMasterData;
import com.bproject.safety.support.masterdata.DemoMasterData;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase B 仓储契约测试：Load → Mutate → Explicit Save。
 *
 * <p>所有 InMemory 仓储必须模拟未来 JDBC 语义：find/list 返回 detached 副本，
 * 不调用 save 直接改返回对象不得影响仓储权威状态；嵌套集合 / 子对象同样 deep-copy。
 * 未来 Jdbc*Repository 应复用同一套测试思想。</p>
 */
class RepositoryCopySemanticsTest {

    private Clock clock;
    private DemoMasterData masterData;
    private DemoDeviceMasterData deviceMasterData;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-04T05:30:00Z"), ZoneOffset.ofHours(8));
        masterData = new DemoMasterData();
        deviceMasterData = new DemoDeviceMasterData(masterData);
    }

    @Test
    @DisplayName("Alert：改 detached 副本不 save 不落库；save 后生效；timeline 嵌套集合 deep-copy")
    void alertCopyOnReadWrite() {
        AlertRepository repository = new InMemoryAlertRepository(clock);
        AlertDemoSeeder.buildSeeds(clock).forEach(repository::save);
        String id = "ALM-20260904-001";
        int originalTimelineSize = repository.findById(id).orElseThrow().timeline.size();

        DemoAlert loaded = repository.findById(id).orElseThrow();
        loaded.statusCode = "CHANGED-WITHOUT-SAVE";
        loaded.timeline.add(TimelineEvent.done("00:00:00", OffsetDateTime.now(clock), "幽灵节点"));
        DemoAlert reread = repository.findById(id).orElseThrow();
        assertThat(reread.statusCode).as("未 save 的标量修改不得持久化").isNotEqualTo("CHANGED-WITHOUT-SAVE");
        assertThat(reread.timeline).hasSize(originalTimelineSize);

        loaded.statusCode = "CHANGED-WITH-SAVE";
        repository.save(loaded);
        DemoAlert afterSave = repository.findById(id).orElseThrow();
        assertThat(afterSave.statusCode).isEqualTo("CHANGED-WITH-SAVE");
        assertThat(afterSave.timeline).hasSize(originalTimelineSize + 1);

        // save 采用 copy-on-write：保存后继续改外部对象不得反向污染仓储
        loaded.timeline.add(TimelineEvent.done("00:00:01", OffsetDateTime.now(clock), "第二个幽灵节点"));
        assertThat(repository.findById(id).orElseThrow().timeline).hasSize(originalTimelineSize + 1);
    }

    @Test
    @DisplayName("Alert：findAll / filter 返回的副本反改也不影响仓储")
    void alertListResultsAreDetached() {
        AlertRepository repository = new InMemoryAlertRepository(clock);
        AlertDemoSeeder.buildSeeds(clock).forEach(repository::save);
        List<DemoAlert> all = repository.findAll();
        long before = repository.findAll().stream().filter(a -> "HACK".equals(a.statusCode)).count();
        all.forEach(a -> a.statusCode = "HACK");
        long after = repository.findAll().stream().filter(a -> "HACK".equals(a.statusCode)).count();
        assertThat(before).isZero();
        assertThat(after).isZero();
    }

    @Test
    @DisplayName("AI Event：detached 副本 + boxes/timeline 嵌套 deep-copy")
    void aiEventCopyOnReadWrite() {
        AiEventRepository repository = new InMemoryAiEventRepository(clock);
        AiDemoSeeder seeder = new AiDemoSeeder(repository, clock, new DemoFeatureGuard(true, true));
        seeder.buildSeeds().forEach(repository::save);
        String id = repository.findAll().get(0).id;

        DemoAiEvent loaded = repository.findById(id).orElseThrow();
        int boxes = loaded.boxes.size();
        loaded.statusCode = "HACK";
        loaded.boxes.add(new AiBox("x", "幽灵框", 1.0, 1, 1, 2, 2, "person"));
        assertThat(repository.findById(id).orElseThrow().statusCode).isNotEqualTo("HACK");
        assertThat(repository.findById(id).orElseThrow().boxes).hasSize(boxes);

        loaded.statusCode = "SAVED";
        repository.save(loaded);
        DemoAiEvent after = repository.findById(id).orElseThrow();
        assertThat(after.statusCode).isEqualTo("SAVED");
        assertThat(after.boxes).hasSize(boxes + 1);
    }

    @Test
    @DisplayName("Personnel：detached 副本语义")
    void personnelCopyOnReadWrite() {
        PersonnelRepository repository = new InMemoryPersonnelRepository();
        ((InMemoryPersonnelRepository) repository).resetDemoData();
        String id = repository.findAll().get(0).id;

        DemoPersonnel loaded = repository.findById(id).orElseThrow();
        String originalArea = loaded.area;
        loaded.area = "幽灵区域";
        loaded.riskCode = "HACK";
        DemoPersonnel reread = repository.findById(id).orElseThrow();
        assertThat(reread.area).as("未 save 的标量修改不得持久化").isEqualTo(originalArea);
        assertThat(reread.riskCode).isNotEqualTo("HACK");
        // activeAlertIds 属于请求级派生投影，不进入仓储副本，这里不对其做持久化断言。

        loaded.area = "已保存区域";
        repository.save(loaded);
        assertThat(repository.findById(id).orElseThrow().area).isEqualTo("已保存区域");
    }

    @Test
    @DisplayName("Fence：polygon / nodes 嵌套集合 deep-copy")
    void fenceCopyOnReadWrite() {
        FenceRepository repository = new InMemoryFenceRepository();
        ((InMemoryFenceRepository) repository).resetDemoData();
        String id = "FENCE-001";
        int points = repository.findById(id).orElseThrow().polygon.size();

        DemoFence loaded = repository.findById(id).orElseThrow();
        loaded.statusCode = "HACK";
        loaded.polygon.add(new FencePoint(1.0, 2.0));
        assertThat(repository.findById(id).orElseThrow().statusCode).isNotEqualTo("HACK");
        assertThat(repository.findById(id).orElseThrow().polygon).hasSize(points);

        loaded.statusCode = "SAVED";
        repository.save(loaded);
        DemoFence after = repository.findById(id).orElseThrow();
        assertThat(after.statusCode).isEqualTo("SAVED");
        assertThat(after.polygon).hasSize(points + 1);
    }

    @Test
    @DisplayName("Collision：detached 副本语义（latestAlertId 等运行态显式 save 才落库）")
    void collisionCopyOnReadWrite() {
        CollisionRepository repository = new InMemoryCollisionRepository(deviceMasterData);
        ((InMemoryCollisionRepository) repository).resetDemoData();
        String id = repository.findAll().get(0).id;

        var loaded = repository.findById(id).orElseThrow();
        loaded.latestAlertId = "ALM-GHOST";
        assertThat(repository.findById(id).orElseThrow().latestAlertId)
                .as("未 save 的运行态修改不得持久化").isNotEqualTo("ALM-GHOST");

        loaded.latestAlertId = "ALM-SAVED";
        repository.save(loaded);
        assertThat(repository.findById(id).orElseThrow().latestAlertId).isEqualTo("ALM-SAVED");
    }

    @Test
    @DisplayName("Rule：versions / params 嵌套集合 deep-copy")
    void ruleCopyOnReadWrite() {
        RuleRepository repository = new InMemoryRuleRepository();
        RuleDemoSeeder.buildSeeds().forEach(repository::save);
        String id = repository.findAll().get(0).id;
        int versions = repository.findById(id).orElseThrow().versions.size();

        DemoRule loaded = repository.findById(id).orElseThrow();
        loaded.statusCode = "HACK";
        loaded.versions.add(new DemoRule.Version("v99.0", "2026-09-04", "幽灵版本", "测试", "草稿", List.of()));
        assertThat(repository.findById(id).orElseThrow().statusCode).isNotEqualTo("HACK");
        assertThat(repository.findById(id).orElseThrow().versions).hasSize(versions);

        loaded.statusCode = "SAVED";
        repository.save(loaded);
        DemoRule after = repository.findById(id).orElseThrow();
        assertThat(after.statusCode).isEqualTo("SAVED");
        assertThat(after.versions).hasSize(versions + 1);
    }

    @Test
    @DisplayName("EdgeNode：detached 副本语义")
    void edgeNodeCopyOnReadWrite() {
        EdgeNodeRepository repository = new InMemoryEdgeNodeRepository(clock, deviceMasterData);
        ((InMemoryEdgeNodeRepository) repository).resetDemoData();
        String id = "EDGE-01";

        DemoEdgeNode loaded = repository.findById(id).orElseThrow();
        loaded.status = "HACK";
        loaded.queueDepth = loaded.queueDepth + 99;
        assertThat(repository.findById(id).orElseThrow().status).isNotEqualTo("HACK");

        loaded.status = "SAVED";
        repository.save(loaded);
        assertThat(repository.findById(id).orElseThrow().status).isEqualTo("SAVED");
    }

    @Test
    @DisplayName("EdgePendingEvent：detached 副本 + localLinkage.actions deep-copy + 稳定排序")
    void edgePendingEventCopyAndOrdering() {
        EdgeEventQueueRepository repository = new InMemoryEdgeEventQueueRepository();
        OffsetDateTime t = OffsetDateTime.now(clock);
        EdgePendingEvent e1 = pendingEvent("EDGE-EVT-002", "EDGE-01", "person-intrusion", t.plusMinutes(2));
        EdgePendingEvent e0 = pendingEvent("EDGE-EVT-001", "EDGE-01", "person-intrusion", t.plusMinutes(1));
        EdgePendingEvent eNull = pendingEvent("EDGE-EVT-003", "EDGE-01", "person-intrusion", null);
        repository.save(e1);
        repository.save(e0);
        repository.save(eNull);

        EdgePendingEvent loaded = repository.findByEventId("EDGE-EVT-001").orElseThrow();
        loaded.status = PendingEventStatuses.SYNCED;
        loaded.localLinkage.actions.add("幽灵联动");
        EdgePendingEvent reread = repository.findByEventId("EDGE-EVT-001").orElseThrow();
        assertThat(reread.status).isEqualTo(PendingEventStatuses.PENDING);
        assertThat(reread.localLinkage.actions).doesNotContain("幽灵联动");

        loaded.status = PendingEventStatuses.FAILED;
        loaded.localLinkage.actions.add("已保存联动");
        repository.save(loaded);
        assertThat(repository.findByEventId("EDGE-EVT-001").orElseThrow().status)
                .isEqualTo(PendingEventStatuses.FAILED);
        assertThat(repository.findByEventId("EDGE-EVT-001").orElseThrow().localLinkage.actions)
                .contains("已保存联动");

        // 稳定排序：edgeOccurredAt 升序，null 最后
        List<String> pending = repository.findPendingForReplay("EDGE-01").stream()
                .map(e -> e.eventId).toList();
        assertThat(pending).containsExactly("EDGE-EVT-001", "EDGE-EVT-002", "EDGE-EVT-003");

        // idempotencyKey 查询（补传去重权威）
        assertThat(repository.findByIdempotencyKey("IDEM-EDGE-EVT-002")).isPresent();
        assertThat(repository.findByIdempotencyKey("NOT-EXISTS")).isEmpty();
    }

    private EdgePendingEvent pendingEvent(String eventId, String nodeId, String type, OffsetDateTime occurredAt) {
        EdgePendingEvent e = new EdgePendingEvent();
        e.eventId = eventId;
        e.edgeNodeId = nodeId;
        e.eventType = type;
        e.businessKey = "BK-" + eventId;
        e.idempotencyKey = "IDEM-" + eventId;
        e.status = PendingEventStatuses.PENDING;
        e.edgeOccurredAt = occurredAt;
        e.receivedAt = occurredAt;
        e.localLinkage = EdgeLocalLinkage.success(new ArrayList<>(List.of("现场声光报警")), false);
        return e;
    }
}
