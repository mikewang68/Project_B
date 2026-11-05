package com.bproject.safety.common.realtime;

import com.bproject.iam.context.*;
import com.bproject.safety.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScopedLiveEventPolicyTest {
  final ScopeTriple own=new ScopeTriple("PROJECT_B","SHEN_TIE","SCS"),other=new ScopeTriple("PROJECT_B","HA_TIE","SCS");
  final ObjectMapper mapper=new ObjectMapper();
  UserContext context(String menu,String level,boolean ro,ScopeSql.Mode mode,List<ScopeTriple> scopes){
    var f=ScopeSql.build(mode,scopes);return new UserContext(2,"u","u","u","u",level,ro,"SCS","PROJECT_B","SHEN_TIE","SCS",mode,scopes,scopes,Set.of(),Set.of(menu),Map.of(menu,Set.of("VIEW")),Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
  }
  ScsLocalPolicy.Actor actor(UserContext c,String nativeCode){return new ScsLocalPolicy.Actor("u","u",Set.of(),Set.of(nativeCode),c.readOnly(),false,c);}
  ScopedLiveEventPolicy.Audience event(ScopeTriple... t){return new ScopedLiveEventPolicy.Audience(Set.of(t),false,true);}
  @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
  @Test void l3AndReadonlyOnlyReceiveOwnTupleWithBothPermissions(){
    for(String level:List.of("L3_SUBSYSTEM_ADMIN","L4_OPERATOR","L1_READONLY"))for(boolean ro:List.of(true,false)){
      var a=actor(context("SCS:people",level,ro,ScopeSql.Mode.SCOPED,List.of(own)),"safety:person:map:view");
      assertTrue(ScopedLiveEventPolicy.permits(a,"person.moved",event(own)));assertFalse(ScopedLiveEventPolicy.permits(a,"person.moved",event(other)));assertFalse(ScopedLiveEventPolicy.permits(a,"person.moved",event(own,other)));
    }
  }
  @Test void l1GlobalStillNeedsExactMenuAndNativeRead(){
    var c=context("SCS:rules","L1_SUPER",false,ScopeSql.Mode.GLOBAL,List.of());
    assertTrue(ScopedLiveEventPolicy.permits(actor(c,"safety:module:rule:view"),"rule.changed",event(other)));
    assertFalse(ScopedLiveEventPolicy.permits(actor(c,"safety:module:alert:view"),"alert.changed",event(own)));
    assertFalse(ScopedLiveEventPolicy.permits(actor(c,"safety:module:alert:view"),"rule.changed",event(own)));
  }
  @Test void l2SelectionChangesTheNextEventAndCannotUseAllowedButUnselectedScope(){
    var a=actor(context("SCS:rules","L2_PROJECT_ADMIN",false,ScopeSql.Mode.SCOPED,List.of(own,other)),"safety:module:rule:view");
    assertTrue(ScopedLiveEventPolicy.permits(a,"rule.changed",event(other)));
    var selected=actor(context("SCS:rules","L2_PROJECT_ADMIN",false,ScopeSql.Mode.SCOPED,List.of(own)),"safety:module:rule:view");
    assertFalse(ScopedLiveEventPolicy.permits(selected,"rule.changed",event(other)));
  }
  @Test void unknownAndUnresolvedEventsRemainDenied(){var a=actor(context("SCS:rules","L1_SUPER",false,ScopeSql.Mode.GLOBAL,List.of()),"safety:module:rule:view");assertFalse(ScopedLiveEventPolicy.permits(a,"system.notice",event(own)));assertFalse(ScopedLiveEventPolicy.permits(a,"rule.changed",ScopedLiveEventPolicy.Audience.denied()));}
  @Test void globalReferenceNotificationRequiresItsSpecificMenu(){
    var e=new ScopedLiveEventPolicy.Audience(Set.of(),true,true);
    assertTrue(ScopedLiveEventPolicy.permits(actor(context("SCS:ai-models","L4_OPERATOR",true,ScopeSql.Mode.SCOPED,List.of(own)),"safety:module:ai:model:view"),"ai.model_selected",e));
    assertFalse(ScopedLiveEventPolicy.permits(actor(context("SCS:ai","L4_OPERATOR",true,ScopeSql.Mode.SCOPED,List.of(own)),"safety:module:ai:view"),"ai.model_selected",e));
  }
  @Test void eventScopeComesFromStoredObjectAndNeverPayloadTuple(){
    var db=mock(NamedParameterJdbcTemplate.class);var policy=new ScopedLiveEventPolicy(db);
    when(db.queryForList(anyString(),anyMap())).thenReturn(List.of(Map.of("rule_code","r","project_id","PROJECT_B","region_id","HA_TIE","subsystem_code","SCS")));
    var e=policy.resolve("rule.changed",mapper.valueToTree(Map.of("ruleId","r","project_id","PROJECT_B","region_id","SHEN_TIE")));
    assertEquals(Set.of(other),e.scopes());assertTrue(e.resolved());
  }
  @Test void partialLegacyMissingAndDatabaseFailureCannotBeBroadcastToV2(){
    var db=mock(NamedParameterJdbcTemplate.class);var policy=new ScopedLiveEventPolicy(db);var data=mapper.valueToTree(Map.of("personnelIds",List.of("p")));
    when(db.queryForList(anyString(),anyMap())).thenReturn(List.of(Map.of("project_id","PROJECT_B")),List.of(),List.of(Map.of("project_id","LEGACY","region_id","UNASSIGNED","subsystem_code","SCS")));
    assertFalse(policy.resolve("person.moved",data).resolved());assertFalse(policy.resolve("person.moved",data).resolved());assertTrue(policy.resolve("person.moved",data).legacy());
    when(db.queryForList(anyString(),anyMap())).thenThrow(new IllegalStateException());assertFalse(policy.resolve("person.moved",data).resolved());
  }
  @Test void onlyCompletelyUnassignedRowsQualifyAsLegacyAndMixedRowsDoNot(){
    var db=mock(NamedParameterJdbcTemplate.class);var policy=new ScopedLiveEventPolicy(db);var empty=new HashMap<String,Object>();empty.put("project_id",null);empty.put("region_id",null);empty.put("subsystem_code",null);
    when(db.queryForList(anyString(),anyMap())).thenReturn(List.of(empty));assertTrue(policy.resolve("person.moved",mapper.valueToTree(Map.of("personnelId","p"))).legacy());
    when(db.queryForList(anyString(),anyMap())).thenReturn(List.of(empty,Map.of("project_id","PROJECT_B","region_id","SHEN_TIE","subsystem_code","SCS")));var mixed=policy.resolve("person.moved",mapper.valueToTree(Map.of("personnelIds",List.of("p","q"))));assertFalse(mixed.resolved());assertFalse(mixed.legacy());
  }
  @Test void aggregateNoticeNeedsTrustedSingleWriteContext(){
    var policy=new ScopedLiveEventPolicy(null);var data=mapper.createObjectNode().put("region_id","SHEN_TIE");
    assertFalse(policy.resolve("collision.changed",data).resolved());var request=new MockHttpServletRequest();RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    ScsCentralScope.bind(request,context("SCS:devices","L3_SUBSYSTEM_ADMIN",false,ScopeSql.Mode.SCOPED,List.of(own)));assertEquals(Set.of(own),policy.resolve("collision.changed",data).scopes());
    ScsCentralScope.bind(request,context("SCS:devices","L1_SUPER",false,ScopeSql.Mode.GLOBAL,List.of()));assertFalse(policy.resolve("collision.changed",data).resolved());
  }
}
