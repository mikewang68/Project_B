package com.bdemo.energy.collection;

import com.bdemo.auth.*;
import com.bdemo.config.*;
import com.bdemo.common.GlobalExceptionHandler;
import com.bdemo.system.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=CollectionHttpTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
 properties={"spring.config.location=optional:classpath:/collection-test.yml","emcs.collection.enabled=true",
 "emcs.collection.ingest-token=test-collection-service-key-at-least-32","server.address=127.0.0.1"})
@AutoConfigureMockMvc
class CollectionHttpTest {
 static final String KEY="test-collection-service-key-at-least-32";
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired CollectionService service;
 @LocalServerPort int port;@TempDir Path temporary;
 @BeforeEach void reset(){jdbc.update("DELETE FROM emcs_collect_reading");jdbc.update("DELETE FROM emcs_collect_event");jdbc.update("UPDATE emcs_collect_device SET area_code='AREA-A',enabled=1");}
 ObjectNode event()throws Exception{
  return (ObjectNode)json.readTree("""
   {"schema_version":"b.device-event.v1","run_id":"run","boot_id":"boot","device_id":"SIM-EXT-04","seq":1,
   "event_id":"run:boot:SIM-EXT-04:1","simulated":true,"replayed":false,"source_ts_ms":null,
   "observed_ts_ms":1700000000000,"timestamp_origin":"gateway_observation",
   "payload":{"points":[{"point_code":"S2-PT-EXT-04-025","value":12.5,"unit":"kW","quality_code":"UNCERTAIN",
   "provenance":{"equipment_code":"EQ-EXT-04"}}]}}
   """);
 }
 void send(ObjectNode event,int code)throws Exception{
  mvc.perform(post("/collection/events").header("X-EMCS-Ingest-Token",KEY).contentType("application/json").content(event.toString())).andExpect(status().is(code));
 }
 @Test void storedEventCanBeReadAndDuplicateDoesNotCreateAnotherReading()throws Exception{
  var e=event();send(e,200);
  mvc.perform(post("/collection/events").header("X-EMCS-Ingest-Token",KEY).contentType("application/json").content(e.toString()))
    .andExpect(jsonPath("$.duplicate").value(true)).andExpect(jsonPath("$.scope").value("EMCS_LOCAL"));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM emcs_collect_reading",Integer.class));
  mvc.perform(get("/collection/event").param("eventId",e.path("event_id").asText()).header("Authorization","Bearer manager"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.points[0].value_json").value("12.5"));
  ((ObjectNode)e.path("payload").path("points").get(0)).put("value",99);send(e,409);
  assertEquals("12.5",jdbc.queryForObject("SELECT value_json FROM emcs_collect_reading",String.class));
 }
 @Test void serviceCredentialAndUserAuthenticationAreIndependent()throws Exception{
  mvc.perform(post("/collection/events").contentType("application/json").content(event().toString())).andExpect(status().isForbidden());
  mvc.perform(post("/collection/events").header("Authorization","Bearer manager").contentType("application/json").content(event().toString())).andExpect(status().isForbidden());
  mvc.perform(get("/collection/events")).andExpect(status().isUnauthorized());
  mvc.perform(get("/collection/events").header("X-EMCS-Ingest-Token",KEY)).andExpect(status().isUnauthorized());
 }
 @Test void invalidPointUnitValueAndPartialBatchRollback()throws Exception{
  var e=event();((ObjectNode)e.path("payload").path("points").get(0)).put("unit","MW");send(e,422);
  e=event();((ObjectNode)e.path("payload").path("points").get(0)).put("value","twelve");send(e,422);
  e=event();var p=e.path("payload").path("points").get(0).deepCopy();((ObjectNode)p).put("point_code","UNKNOWN");
  ((com.fasterxml.jackson.databind.node.ArrayNode)e.path("payload").path("points")).add(p);send(e,422);
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM emcs_collect_event",Integer.class));
 }
 @Test void badQualityNullIsPreservedButGoodNullIsRejected()throws Exception{
  var e=event();var p=(ObjectNode)e.path("payload").path("points").get(0);p.putNull("value").put("quality_code","GOOD");send(e,422);
  p.put("quality_code","BAD");send(e,200);
  assertEquals("null",jdbc.queryForObject("SELECT value_json FROM emcs_collect_reading",String.class));
  assertEquals("BAD",jdbc.queryForObject("SELECT quality_code FROM emcs_collect_reading",String.class));
 }
 @Test void disabledDevicesLiveDataAndConflictingIdentityAreRejected()throws Exception{
  var e=event();jdbc.update("UPDATE emcs_collect_device SET enabled=0");send(e,403);
  jdbc.update("UPDATE emcs_collect_device SET enabled=1");e.put("simulated",false);send(e,403);
  e=event();e.put("event_id","another-id");send(e,422);
  e=event();e.put("observed_ts_ms",Long.MAX_VALUE);send(e,422);
 }
 @Test void areaRestrictedUserCannotReadOtherAreaEvents()throws Exception{
  jdbc.update("UPDATE emcs_collect_device SET area_code='AREA-B' WHERE device_id='SIM-EXT-04'");send(event(),200);
  mvc.perform(get("/collection/events").header("Authorization","Bearer dispatch")).andExpect(jsonPath("$.data.total").value(0));
  mvc.perform(get("/collection/event").param("eventId","run:boot:SIM-EXT-04:1").header("Authorization","Bearer dispatch")).andExpect(status().isNotFound());
  mvc.perform(get("/collection/events").header("Authorization","Bearer manager")).andExpect(jsonPath("$.data.total").value(1));
 }
 @Test void concurrentIdenticalEventsCommitOnce()throws Exception{
  byte[] wire=event().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
  try{
   var calls=new ArrayList<java.util.concurrent.Callable<Map<String,Object>>>();for(int n=0;n<4;n++)calls.add(()->service.accept(wire));
   long created=0;for(var result:pool.invokeAll(calls))if(Boolean.FALSE.equals(result.get().get("duplicate")))created++;
   assertEquals(1,created);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM emcs_collect_event",Integer.class));
  }finally{pool.shutdownNow();}
 }
 @Test @EnabledIfEnvironmentVariable(named="EMCS_PYTHON",matches=".+")
 void daemonCapturesOfflineThenDeliversAllSixDevicesThroughRealHttp()throws Exception{
  Path script=Path.of("../tools/collection/collector.py").toAbsolutePath(),token=temporary.resolve("service.token"),out=temporary.resolve("outbox");
  Files.writeString(token,KEY);
  run(script,"--output",out.toString(),"--capture-only");
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM emcs_collect_event",Integer.class));
  run(script,"--output",out.toString(),"--flush-only","--emcs-url","http://127.0.0.1:"+port,"--token-file",token.toString());
  run(script,"--output",out.toString(),"--flush-only","--emcs-url","http://127.0.0.1:"+port,"--token-file",token.toString());
  assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM emcs_collect_event",Integer.class));
  assertEquals(181,jdbc.queryForObject("SELECT COUNT(*) FROM emcs_collect_reading",Integer.class));
  mvc.perform(get("/collection/events").header("Authorization","Bearer manager")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(6));
 }
 void run(Path script,String... args)throws Exception{
  var command=new ArrayList<String>(List.of(System.getenv("EMCS_PYTHON"),script.toString()));command.addAll(List.of(args));
  var process=new ProcessBuilder(command).redirectErrorStream(true).start();
  if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();fail("Collector timed out");}
  assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
 }
 @Configuration(proxyBeanMethods=false)
 @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class})
 @Import({SecurityConfig.class,CollectionController.class,CollectionService.class,GlobalExceptionHandler.class})
 static class Config {
  @Bean javax.sql.DataSource dataSource()throws Exception{
   var ds=new DriverManagerDataSource("jdbc:h2:mem:collect;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
   try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("../sql/027_device_collection.sql")));}return ds;
  }
  @Bean org.springframework.transaction.PlatformTransactionManager transactions(javax.sql.DataSource ds){return new DataSourceTransactionManager(ds);}
  @Bean JwtService jwt(){var jwt=mock(JwtService.class);when(jwt.parse("manager")).thenReturn(new AuthenticatedUser(42,"manager"));when(jwt.parse("dispatch")).thenReturn(new AuthenticatedUser(43,"dispatch"));return jwt;}
  @Bean SystemUserMapper users(){
   var users=mock(SystemUserMapper.class);
   for(long id:new long[]{42,43}){when(users.findById(id)).thenReturn(Optional.of(new UserAccount(id,1L,"user","User","","","0","0")));when(users.findRoleKeys(id)).thenReturn(List.of(id==42?"energy_mgr":"dispatch"));when(users.findPermissions(id)).thenReturn(List.of("energy:collect:raw:view"));}return users;
  }
  @Bean JwtAuthenticationFilter jwtFilter(JwtService jwt,SystemUserMapper users){return new JwtAuthenticationFilter(jwt,users);}
  @Bean EmcsSsoSessions sso(){return mock(EmcsSsoSessions.class);}
  @Bean SecurityAuditAccessDeniedHandler denied(){return mock(SecurityAuditAccessDeniedHandler.class);}
 }
}
