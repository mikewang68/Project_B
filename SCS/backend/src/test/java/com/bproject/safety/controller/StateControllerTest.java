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
  @Test void aSnapshotCannotBypassPerModuleReadPermissions(){
    var people=mock(PersonnelRepository.class);var fences=mock(FenceRepository.class);
    var devices=mock(CollisionRepository.class);var events=mock(AiEventRepository.class);
    var alerts=mock(AlertRepository.class);var rules=mock(RuleRepository.class);
    var request=new org.springframework.mock.web.MockHttpServletRequest();
    request.setAttribute("SCS_ACTOR",new com.bproject.safety.config.ScsLocalPolicy.Actor("subject","safety",java.util.Set.of("OPERATOR"),java.util.Set.of("safety:home:map:view","safety:module:alert:view"),false,false));
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
    try{
      var controller=new StateController(Clock.systemUTC(),people,fences,devices,events,alerts,rules,new DemoFeatureGuard(false,false));
      var state=controller.getState();assertEquals(0L,((Map<?,?>)state.get("aiEventWindow")).get("total"));
      verify(alerts).findAll();verifyNoInteractions(people,fences,devices,events,rules);
      for(String field:List.of("people","fences","devices","aiEvents","rules"))assertEquals(List.of(),state.get(field));
    }finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
  }
}
