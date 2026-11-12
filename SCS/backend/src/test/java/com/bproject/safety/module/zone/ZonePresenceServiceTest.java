package com.bproject.safety.module.zone;

import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.common.realtime.LiveEventGate;
import com.bproject.safety.common.realtime.WebSocketSessionRegistry;
import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.FencePoint;
import com.bproject.safety.module.fence.model.FenceStatuses;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
import com.bproject.safety.module.personnel.service.PersonnelPositionService;
import com.bproject.safety.module.zone.model.ZonePresence;
import com.bproject.safety.module.zone.service.ZonePresenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** 区域实时在场人数：固定作业区矩形归属 + 已生效临时围栏多边形判定（含隔离带）。 */
class ZonePresenceServiceTest {

    private static final Clock CLOCK = Clock.system(ZoneId.of("Asia/Shanghai"));

    private final PersonnelRepository personnelRepository = org.mockito.Mockito.mock(PersonnelRepository.class);
    private final FenceRepository fenceRepository = org.mockito.Mockito.mock(FenceRepository.class);
    private final PersonnelPositionService positions = new PersonnelPositionService(
            personnelRepository,
            new DomainLivePublisher(new WebSocketSessionRegistry(), new ObjectMapper(), CLOCK, new LiveEventGate()),
            CLOCK);
    private final ZonePresenceService service = new ZonePresenceService(personnelRepository, fenceRepository, positions);

    @BeforeEach
    void seed() {
        when(personnelRepository.findAll()).thenReturn(List.of(
                person("P-1001", 50, 50), person("P-1002", 50, 50), person("P-1003", 50, 50)));
        when(fenceRepository.findAll()).thenReturn(List.of());
    }

    private static DemoPersonnel person(String id, double x, double y) {
        DemoPersonnel person = new DemoPersonnel();
        person.id = id;
        person.name = "人员" + id;
        person.x = x;
        person.y = y;
        return person;
    }

    private void stubPerson(String id, double x, double y) {
        when(personnelRepository.findById(id)).thenReturn(Optional.of(person(id, x, y)));
    }

    @Test
    void fixedAreaCountsFollowLivePositions() {
        stubPerson("P-1001", 0, 0);
        stubPerson("P-1002", 0, 0);
        stubPerson("P-1003", 0, 0);
        assertTrue(positions.apply("P-1001", 10.0, 20.0, null, null)); // 装卸区 A (5,10,33,36)
        assertTrue(positions.apply("P-1002", 80.0, 20.0, null, null)); // 装卸区 B (70,10,95,36)
        assertTrue(positions.apply("P-1003", 50.0, 50.0, null, null)); // 兜底车辆通道

        List<ZonePresence> presence = service.list();

        assertEquals(1, countOf(presence, "装卸区 A"));
        assertEquals(1, countOf(presence, "装卸区 B"));
        assertEquals(1, countOf(presence, "车辆通道"));
        assertEquals(0, countOf(presence, "龙门吊作业区"));
        assertEquals("fixed", presence.stream().filter(p -> p.name().equals("装卸区 A")).findFirst().orElseThrow().kind());
    }

    @Test
    void effectiveFenceCountsPeopleInsidePolygonAndBuffer() {
        stubPerson("P-1001", 0, 0);
        stubPerson("P-1002", 0, 0);
        stubPerson("P-1003", 0, 0);
        assertTrue(positions.apply("P-1001", 10.0, 20.0, null, null)); // 核心区内
        assertTrue(positions.apply("P-1002", 10.0, 14.0, null, null)); // 核心区外、隔离带内
        assertTrue(positions.apply("P-1003", 90.0, 90.0, null, null)); // 远处

        DemoFence fence = new DemoFence();
        fence.id = "FENCE-TEST-001";
        fence.name = "临时吊装区围栏";
        fence.statusCode = FenceStatuses.EFFECTIVE;
        fence.polygon = List.of(new FencePoint(5, 15), new FencePoint(15, 15),
                new FencePoint(15, 30), new FencePoint(5, 30));
        fence.bufferPolygon = List.of(new FencePoint(3, 12), new FencePoint(17, 12),
                new FencePoint(17, 32), new FencePoint(3, 32));
        when(fenceRepository.findAll()).thenReturn(List.of(fence));

        List<ZonePresence> presence = service.list();

        ZonePresence effective = presence.stream()
                .filter(p -> "FENCE-TEST-001".equals(p.fenceId()))
                .findFirst().orElseThrow();
        assertEquals(1, effective.personCount());
        assertEquals(1, effective.bufferCount());
        assertEquals("已生效", effective.fenceStatus());
        assertEquals("fence", effective.kind());
    }

    @Test
    void draftAndDisabledFencesAreExcluded() {
        stubPerson("P-1001", 0, 0);
        assertTrue(positions.apply("P-1001", 10.0, 20.0, null, null));

        DemoFence draft = new DemoFence();
        draft.id = "FENCE-DRAFT";
        draft.name = "草稿围栏";
        draft.statusCode = FenceStatuses.DRAFT;
        draft.polygon = List.of(new FencePoint(0, 0), new FencePoint(99, 0),
                new FencePoint(99, 99), new FencePoint(0, 99));
        DemoFence disabled = new DemoFence();
        disabled.id = "FENCE-DISABLED";
        disabled.name = "停用围栏";
        disabled.statusCode = FenceStatuses.DISABLED;
        disabled.polygon = List.of(new FencePoint(0, 0), new FencePoint(99, 0),
                new FencePoint(99, 99), new FencePoint(0, 99));
        when(fenceRepository.findAll()).thenReturn(List.of(draft, disabled));

        assertTrue(service.list().stream().noneMatch(p -> "fence".equals(p.kind())));
    }

    @Test
    void unreportedPersonCountsByLedgerPositionAndDeletedPersonExcluded() {
        // P-1001 未上报 → 台账坐标 (50,50) → 车辆通道
        when(personnelRepository.findAll()).thenReturn(List.of(
                person("P-1001", 50, 50), person("P-GONE", 10, 20)));
        DemoPersonnel gone = person("P-GONE", 10, 20);
        gone.isDeleted = true;
        when(personnelRepository.findAll()).thenReturn(List.of(
                person("P-1001", 50, 50), gone));

        List<ZonePresence> presence = service.list();
        assertEquals(1, countOf(presence, "车辆通道"));
        assertEquals(0, countOf(presence, "装卸区 A")); // 已删除人员不得计入
    }

    private static int countOf(List<ZonePresence> presence, String name) {
        return presence.stream().filter(p -> p.name().equals(name))
                .findFirst().orElseThrow().personCount();
    }
}
