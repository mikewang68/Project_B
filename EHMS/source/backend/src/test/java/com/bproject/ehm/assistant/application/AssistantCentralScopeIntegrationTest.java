package com.bproject.ehm.assistant.application;

import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.assistant.ports.AssistantModelPort;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.platform.security.*;
import com.bproject.iam.context.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:assistant-restored;DB_CLOSE_DELAY=-1",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "ehm.persistence.auto-ddl=true","ehm.auth.mode=disabled","ehm.audit.endpoint=","ehm.ai.enabled=false",
 "ehm.sample-data.enabled=false","ehm.telemetry.adapter=openGemini","ehm.simulation.enabled=false",
 "ehm.project.default-id=PROJECT_B","ehm.config.path=target/no-assistant-restoration-config.json"})
@ActiveProfiles("server")
class AssistantCentralScopeIntegrationTest {
 @Autowired AssistantApplicationService assistant;@Autowired AssistantDataTools tools;
 @Autowired OpenGaussJsonStore store;@Autowired JdbcTemplate db;
 @MockBean AssistantModelPort model;
 UserContext context(String subject,String region,String version,Map<String,Set<String>> menus){
  var t=new ScopeTriple("PROJECT_B",region,"EHMS");var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(t));
  return new UserContext(2,subject,subject,subject,subject,"L4_OPERATOR",true,"EHMS","PROJECT_B",region,"EHMS",ScopeSql.Mode.SCOPED,
   List.of(t),List.of(t),Set.of(),menus.keySet(),menus,Set.of(),version,f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
 }
 UserContext user(String subject,String region,String version){return context(subject,region,version,Map.of("EHMS:fleet",Set.of("VIEW"),"EHMS:ai-assistant",Set.of("VIEW")));}
 void put(String region,String code,String name)throws Exception{
  var c=user("seed",region,"1"); // Fixture writes use the same scope tuple, but not a read-only identity.
  c=new UserContext(c.contextVersion(),c.userId(),c.username(),c.userCode(),c.displayName(),c.adminLevel(),false,c.serviceAudience(),
   c.projectId(),c.regionId(),c.subsystemCode(),c.scopeMode(),c.allowedScopes(),c.activeScope(),c.roles(),c.visibleMenuKeys(),c.menuOperations(),c.managementCapabilities(),c.authorizationVersion(),c.my_info(),c.my_project_zone_info(),c.my_info_template(),c.my_project_zone_info_template(),c.scope_params());
  try(var p=ProjectContext.bind("PROJECT_B");var scope=EhmCentralScope.bind(c)){
   store.save("asset",code,Asset.create(code,name,"fixture","virtual",null,"fixture",Instant.now()));
  }
 }
 @BeforeEach void prepare()throws Exception{
  org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); // direct service integration, not an HTTP identity fixture
  db.update("DELETE FROM ehm.aggregate_store");put("REGION_A","A-ONLY","[virtual]region A");put("REGION_B","B-ONLY","[virtual]region B");
  when(model.status()).thenReturn(new AssistantModelPort.ModelStatus(false,false,false,"fixture","fixture","disabled","No external model in this test"));
 }
 @Test void toolsAndActivityOnlyReadCurrentRegionAndUnknownSelectionCannotReachModel()throws Exception{
  try(var p=ProjectContext.bind("PROJECT_B");var scope=EhmCentralScope.bind(user("U1","REGION_A","1"))){
   assertEquals(List.of("A-ONLY"),tools.devices().stream().map(d->d.code()).toList());
   var answer=assistant.answer("全部设备有哪些？");assertEquals("local-data",answer.mode());
   assertTrue(answer.answer().contains("A-ONLY"));assertFalse(answer.answer().contains("B-ONLY"));
   assertNull(tools.query("query_ehm","{\"category\":\"alarms\"}",new AssistantPrivacy(),1).source());
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->assistant.answer("检查",null,"B-ONLY"));
   assertEquals(1L,store.activity().stream().filter(r->r.get("type").equals("asset")).findFirst().orElseThrow().get("total"));
  }
  verify(model,never()).complete(anyList(),anyList());
 }
 @Test void conversationsCannotBeReadReusedOrDeletedAcrossUserRegionOrAuthorizationVersion()throws Exception{
  String id;
  try(var p=ProjectContext.bind("PROJECT_B");var scope=EhmCentralScope.bind(user("U1","REGION_A","1"))){id=assistant.answer("全部设备").sessionId();assertEquals(2,assistant.history(id).size());}
  for(var stranger:List.of(user("U2","REGION_A","1"),user("U1","REGION_B","1"),user("U1","REGION_A","2"))){
   try(var p=ProjectContext.bind("PROJECT_B");var scope=EhmCentralScope.bind(stranger)){
    assertTrue(assistant.history(id).isEmpty());assistant.clear(id);
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->assistant.answer("继续",id,null));
   }
  }
  try(var p=ProjectContext.bind("PROJECT_B");var scope=EhmCentralScope.bind(user("U1","REGION_A","1"))){assertEquals(2,assistant.history(id).size());}
 }
}
