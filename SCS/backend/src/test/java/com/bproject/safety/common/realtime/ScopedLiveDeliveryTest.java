package com.bproject.safety.common.realtime;

import com.bproject.iam.context.*;
import com.bproject.safety.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScopedLiveDeliveryTest {
  ScsLocalPolicy.Actor scoped(){
    var t=new ScopeTriple("PROJECT_B","SHEN_TIE","SCS");var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(t));
    var c=new UserContext(2,"u","u","u","u","L4_OPERATOR",true,"SCS","PROJECT_B","SHEN_TIE","SCS",ScopeSql.Mode.SCOPED,List.of(t),List.of(t),Set.of(),Set.of("SCS:rules"),Map.of("SCS:rules",Set.of("VIEW")),Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
    return new ScsLocalPolicy.Actor("u","u",Set.of(),Set.of("safety:module:rule:view"),true,false,c);
  }
  WebSocketSession socket(String name){var s=mock(WebSocketSession.class);when(s.getId()).thenReturn(name);when(s.isOpen()).thenReturn(true);when(s.getAttributes()).thenReturn(Map.of());return s;}
  @Test void scopedDeliveryIsOnlyAnInvalidationAndRevocationStopsNextEvent()throws Exception{
    var authorization=mock(LiveSessionAuthorization.class);var events=mock(ScopedLiveEventPolicy.class);var registry=new WebSocketSessionRegistry();
    ReflectionTestUtils.setField(registry,"authorization",authorization);ReflectionTestUtils.setField(registry,"scopedEvents",events);
    var s=socket("scoped");registry.connect(s);when(authorization.current(any())).thenReturn(scoped()).thenThrow(new IllegalStateException("revoked"));
    when(events.resolve(eq("rule.changed"),any())).thenReturn(new ScopedLiveEventPolicy.Audience(Set.of(new ScopeTriple("PROJECT_B","SHEN_TIE","SCS")),false,true));
    var message=new TextMessage("{\"type\":\"rule.changed\",\"eventId\":\"event-1\",\"ts\":\"2026-11-05\",\"data\":{\"ruleId\":\"private-id\",\"title\":\"PRIVATE_PAYLOAD\"}}");
    registry.broadcast(message);var sent=org.mockito.ArgumentCaptor.forClass(WebSocketMessage.class);verify(s).sendMessage(sent.capture());var body=new ObjectMapper().readTree(sent.getValue().getPayload().toString());assertEquals(true,body.path("data").path("refresh").asBoolean());assertEquals(1,body.path("data").size());assertFalse(sent.getValue().getPayload().toString().contains("PRIVATE_PAYLOAD"));
    registry.broadcast(message);verify(s,times(1)).sendMessage(any());verify(s).close(CloseStatus.POLICY_VIOLATION);
  }
  @Test void legacySocketNeverReceivesOwnedV2Event()throws Exception{
    var auth=mock(LiveSessionAuthorization.class);var events=mock(ScopedLiveEventPolicy.class);var registry=new WebSocketSessionRegistry();ReflectionTestUtils.setField(registry,"authorization",auth);ReflectionTestUtils.setField(registry,"scopedEvents",events);var s=socket("old");registry.connect(s);var a=new ScsLocalPolicy.Actor("old","old",Set.of("ADMIN"),Set.of(),false,true);when(auth.current(any())).thenReturn(a);when(auth.permits(a,"rule.changed")).thenReturn(true);when(events.resolve(anyString(),any())).thenReturn(new ScopedLiveEventPolicy.Audience(Set.of(new ScopeTriple("PROJECT_B","SHEN_TIE","SCS")),false,true));registry.broadcast(new TextMessage("{\"type\":\"rule.changed\",\"data\":{}}"));verify(s,never()).sendMessage(any());
  }
  @Test void v2NeverUsesOutageGraceEvenWhenLegacyGraceConfigured(){
    var iam=mock(ScsIamTicketValidator.class);var policy=mock(ScsLocalPolicy.class);var auth=new LiveSessionAuthorization(iam,policy,true,600);String id=UUID.randomUUID().toString(),sid=UUID.randomUUID().toString();ScsSessionRegistry.bind(id,"u",sid,Set.of(),Set.of(),"scs");var s=socket(id);when(s.getAttributes()).thenReturn(Map.of("SCS_WS_BINDING",ScsSessionRegistry.get(id)));var claims=Map.<String,Object>of("sub","u");when(iam.current("u",sid)).thenReturn(claims).thenThrow(new ScsIamTicketValidator.IamUnavailableException("temporary"));when(policy.resolve(claims)).thenReturn(scoped());assertNotNull(auth.current(s));assertThrows(LiveSessionAuthorization.DeliverySuspendedException.class,()->auth.current(s));ScsSessionRegistry.revokeByIamSid(sid);
  }
  @Test void unresolvedEventsCannotFallBackToLegacyPayload()throws Exception{
    var auth=mock(LiveSessionAuthorization.class);var events=mock(ScopedLiveEventPolicy.class);var registry=new WebSocketSessionRegistry();ReflectionTestUtils.setField(registry,"authorization",auth);ReflectionTestUtils.setField(registry,"scopedEvents",events);var s=socket("legacy");registry.connect(s);var a=new ScsLocalPolicy.Actor("old","old",Set.of("ADMIN"),Set.of(),false,true);when(auth.current(any())).thenReturn(a);when(auth.permits(a,"rule.changed")).thenReturn(true);
    when(events.resolve(anyString(),any())).thenReturn(ScopedLiveEventPolicy.Audience.denied()).thenReturn(ScopedLiveEventPolicy.Audience.verifiedLegacy());var message=new TextMessage("{\"type\":\"rule.changed\",\"data\":{\"title\":\"PRIVATE\"}}");registry.broadcast(message);verify(s,never()).sendMessage(any());registry.broadcast(message);verify(s,times(1)).sendMessage(any());
  }
}
