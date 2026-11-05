package com.bproject.safety.config;

import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;

class ScsCentralScopeTest {
  UserContext context(Set<String> actions,boolean readonly){
    var triple=new ScopeTriple("PROJECT_B","SHEN_TIE","SCS");var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(triple));
    return new UserContext(2,"subject","operator","subject","operator","L4_OPERATOR",readonly,"SCS",
      triple.projectId(),triple.regionId(),triple.subsystemCode(),ScopeSql.Mode.SCOPED,List.of(triple),List.of(triple),
      Set.of("job"),Set.of("SCS:rules"),Map.of("SCS:rules",actions),Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
  }
  @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();}
  @Test void rejectsAMismatchedServiceAudienceAndUsesTheCentralTupleInRootQueries(){
    var c=context(Set.of("VIEW"),false);var mapper=new ObjectMapper();
    assertEquals("SHEN_TIE",ScsCentralScope.validate(c,"subject",mapper).regionId());
    var raw=mapper.valueToTree(c);((com.fasterxml.jackson.databind.node.ObjectNode)raw).put("serviceAudience","WMS");
    assertThrows(ScsIamTicketValidator.InvalidTicketException.class,()->ScsCentralScope.validate(raw,"subject",mapper));
    var request=new MockHttpServletRequest();ScsCentralScope.bind(request,c);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    var sql=ScsCentralScope.predicate(ScsCentralScope.Root.RULE,"safety_rule");
    assertTrue(sql.contains("o.id=safety_rule.id"));assertTrue(sql.contains("o.region_id = 'SHEN_TIE'"));
    assertThrows(IllegalArgumentException.class,()->ScsCentralScope.predicate(ScsCentralScope.Root.RULE,"clientSql"));
  }
  @Test void actionsAndUnadaptedResourcesRemainDeniedEvenForAnAdminShapedContext(){
    var add=context(Set.of("VIEW","ADD"),false);
    assertTrue(ScsCentralScope.permits(add,"POST","/api/v1/rules"));
    assertFalse(ScsCentralScope.permits(add,"POST","/api/v1/rules/one/publish"));
    assertFalse(ScsCentralScope.permits(add,"GET","/api/v1/alerts"));
    assertFalse(ScsCentralScope.permits(add,"POST","/api/v1/rules/one/unreviewed-action"));
    assertTrue(ScsCentralScope.permits(context(Set.of("CONFIRM"),false),"POST","/api/v1/rules/one/publish"));
    assertFalse(ScsCentralScope.permits(context(Set.of("CONFIRM"),true),"POST","/api/v1/rules/one/publish"));
  }
  @Test void newlyPreservedDeviceResourcesCannotBypassV2ScopeThroughLegacyPermissions(){
    var c=context(Set.of("VIEW","ADD","EDIT","CONFIRM"),false);
    for(String path:List.of("/api/v1/zones","/api/v1/personnel/uwb-stations",
        "/api/v1/personnel/live","/api/v1/collision/devices"))
      assertFalse(ScsCentralScope.permits(c,"GET",path),path);
    assertTrue(ScsCentralScope.permits(c,"GET","/ws/live"));
    assertFalse(ScsCentralScope.permits(context(Set.of("EDIT"),false),"GET","/ws/live"));
    for(String path:List.of("device-state","radar","person-position","uwb-station","zone-state"))
      assertFalse(ScsCentralScope.permits(c,"POST","/api/v1/ingest/"+path),path);
    var actor=new ScsLocalPolicy.Actor("subject","administrator",Set.of("ADMIN"),Set.of("*"),false,true,c);
    var live=new com.bproject.safety.common.realtime.LiveSessionAuthorization(null,null,true);
    for(String type:List.of("zone.changed","person.moved","collision.changed","rule.published"))
      assertFalse(live.permits(actor,type),type);
  }
}
