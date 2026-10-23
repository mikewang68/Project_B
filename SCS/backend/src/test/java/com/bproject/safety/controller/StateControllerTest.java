package com.bproject.safety.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.collision.repository.CollisionRepository;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
import com.bproject.safety.module.rule.repository.RuleRepository;
import com.bproject.safety.support.demo.DemoFeatureGuard;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StateControllerTest {
  @Test void eventWindowIsExplicitAndNeverReadsTheFullHistory() {
    var events=mock(AiEventRepository.class);
    when(events.findAll()).thenThrow(new AssertionError("Snapshot must not load all events"));
    when(events.findRecent(50)).thenReturn(List.of());when(events.count()).thenReturn(100_000L);
    var controller=new StateController(Clock.systemUTC(),mock(PersonnelRepository.class),
        mock(FenceRepository.class),mock(CollisionRepository.class),events,mock(AlertRepository.class),
        mock(RuleRepository.class),new DemoFeatureGuard(false,false));
    var state=controller.getState();var window=(Map<?,?>)state.get("aiEventWindow");
    assertEquals(50,window.get("limit"));assertEquals(100_000L,window.get("total"));
    assertEquals(true,window.get("truncated"));verify(events,never()).findAll();
  }
  @Test
  void emptyBusinessDatabaseDoesNotBecomeSyntheticHealthyEquipment() {
    var controller = new StateController(Clock.systemUTC(), mock(PersonnelRepository.class),
        mock(FenceRepository.class), mock(CollisionRepository.class), mock(AiEventRepository.class),
        mock(AlertRepository.class), mock(RuleRepository.class), new DemoFeatureGuard(false, false));
    var state = controller.getState();
    for (String key : List.of("people", "fences", "devices", "aiEvents", "alarms", "rules", "scenarios", "auditLogs"))
      assertEquals(List.of(), state.get(key), key);
    assertEquals("partial", state.get("dataQuality"));
    assertEquals("未接入", ((Map<?,?>) state.get("workPlan")).get("status"));
    assertEquals(false, ((Map<?,?>) state.get("system")).get("cloudConnected"));
  }
}
