package com.bproject.ehm.platform.security;
import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Local contract tests only; no real IAM requests, sessions, credentials or registry changes. */
class EhmDemoSsoContractTest {
 @TempDir Path directory;
 final ObjectMapper json=new ObjectMapper();final String subject="0123456789abcdef0123456789abcdef";
 final ScopeTriple demo=new ScopeTriple("PROJECT_B","EHMS_DEMO","EHMS"),shen=new ScopeTriple("PROJECT_B","SHEN_TIE","EHMS");
 UserContext context(String level,boolean readonly,List<ScopeTriple> allowed,List<ScopeTriple> active,Map<String,Set<String>> menus){
  boolean l1=level.startsWith("L1_");var sql=ScopeSql.build(ScopeSql.Mode.SCOPED,active);
  return new UserContext(2,subject,"fixture","fixture","fixture",level,readonly,"EHMS",l1?null:"PROJECT_B",l1?null:"SHEN_TIE",l1?null:"EHMS",ScopeSql.Mode.SCOPED,
   allowed,active,Set.of(),menus.keySet(),menus,Set.of(),"fixture-v930",sql.my_info(),sql.my_project_zone_info(),sql.my_info_template(),sql.my_project_zone_info_template(),sql.scope_params());
 }
 EhmSsoSessions service(List<String> projects)throws Exception{
  Path file=directory.resolve("fixture-bindings.json");json.writeValue(file.toFile(),Map.of(subject,Map.of("enabled",true,"localSubject","fixture","role","SUPER_ADMIN","projectIds",projects,"permissions",List.of("ehealth:asset:archive:view"))));
  return new EhmSsoSessions(json,"http://127.0.0.1:1","fixture-service-identity-no-real-token",file.toString());
 }
 EhmSsoSessions.Actor resolve(EhmSsoSessions service,UserContext context){
  ObjectNode data=json.createObjectNode().put("subject",subject).put("sessionId","fixture-sid").put("localSubject","fixture").put("audience","ehms").put("readOnly",context.readOnly()).put("superAdmin",!context.readOnly());
  data.putArray("projectIds").add("PROJECT_B");data.putArray("permissions");data.set("userContext",json.valueToTree(context));
  return ReflectionTestUtils.invokeMethod(service,"resolve",data,subject,"fixture-sid","fixture","SUPER_ADMIN");
 }
 @Test void l1ProjectRegistryWithoutRegionFieldsAcceptsVerifiedDemoContext()throws Exception{
  var actor=resolve(service(List.of("PROJECT_B")),context("L1_SUPER",false,List.of(shen,demo),List.of(demo),Map.of("EHMS:fleet",Set.of("VIEW","ADD"))));
  assertEquals(List.of(demo),actor.context().activeScope());assertTrue(actor.admin());
  try(var p=ProjectContext.bind("PROJECT_B");var c=EhmCentralScope.bind(actor.context())){assertEquals(demo,EhmCentralScope.writeTuple("PROJECT_B"));assertTrue(EhmCentralScope.readPredicate().contains("EHMS_DEMO"));assertFalse(EhmCentralScope.readPredicate().contains("SHEN_TIE"));}
 }
 @Test void nativeSuperAdminStillCannotBypassMissingCentralAction()throws Exception{
  var actor=resolve(service(List.of("PROJECT_B")),context("L1_SUPER",false,List.of(demo),List.of(demo),Map.of("EHMS:fleet",Set.of("VIEW"))));
  assertTrue(EhmBusinessPermissions.permits(actor,"GET","/api/ehm/v1/devices"));assertFalse(EhmBusinessPermissions.permits(actor,"POST","/api/ehm/v1/devices"));
 }
 @Test void l1ReadonlyUsesDemoScopeButRemainsUnableToWrite()throws Exception{
  var actor=resolve(service(List.of("PROJECT_B")),context("L1_READONLY",true,List.of(demo),List.of(demo),Map.of("EHMS:fleet",Set.of("VIEW","ADD"))));
  assertFalse(actor.admin());assertTrue(EhmBusinessPermissions.permits(actor,"GET","/api/ehm/v1/devices"));assertFalse(EhmBusinessPermissions.permits(actor,"POST","/api/ehm/v1/devices"));
 }
 @Test void centralContextCannotGrantAnUnmappedProject()throws Exception{
  assertEquals("SSO_PROJECT_NOT_GRANTED",assertThrows(ResponseStatusException.class,()->resolve(service(List.of("OTHER")),context("L1_SUPER",false,List.of(demo),List.of(demo),Map.of()))).getReason());
 }
 @Test void multipleSelectedRegionsCannotBeUsedForBusinessWrites()throws Exception{
  var actor=resolve(service(List.of("PROJECT_B")),context("L1_SUPER",false,List.of(shen,demo),List.of(shen,demo),Map.of()));
  try(var c=EhmCentralScope.bind(actor.context())){assertThrows(ResponseStatusException.class,()->EhmCentralScope.writeTuple("PROJECT_B"));}
 }
 @Test void fixedRegionalOperatorsCannotSimplyAppendADifferentRegion()throws Exception{
  for(String level:List.of("L3_SUBSYSTEM","L4_OPERATOR"))
   assertEquals("SSO_CENTRAL_SCOPE_REJECTED",assertThrows(ResponseStatusException.class,()->resolve(service(List.of("PROJECT_B")),context(level,false,List.of(shen,demo),List.of(demo),Map.of()))).getReason());
 }
}
