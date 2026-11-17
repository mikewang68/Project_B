package com.bproject.ehm.platform.persistence.opengauss;
import com.bproject.ehm.platform.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class BoundedHistoryQueryTest {
 record Event(String assetCode,String pointCode,Instant generatedAt,double value){}
 ObjectMapper mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
 OpenGaussJsonStore store;JdbcTemplate jdbc;
 @BeforeEach void init(){jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));store=new OpenGaussJsonStore(jdbc,mapper,"ehm","aggregate_store",true);store.initialize();}
 @Test void boundedHistoryUsesEventTimeNotLastReviewTimeAndExcludesOtherScopes()throws Exception{
  var contexts=new CentralAggregateIsolationTest();Instant start=Instant.parse("2026-11-01T00:00:00Z");
  try(var p=ProjectContext.bind("PROJECT_B")){
   for(String region:List.of("EHMS_DEMO","SHEN_TIE"))try(var scope=EhmCentralScope.bind(contexts.context("PROJECT_B",region,false))){
    for(int i=0;i<1200;i++)store.save("health-assessment","H-"+i,new Event(i%2==0?"A":"B","P",start.plusSeconds(i),i));
    store.save("health-assessment","H-0",new Event("A","P",start,0)); // later review/write must not become latest assessment
   }
   try(var scope=EhmCentralScope.bind(contexts.context("PROJECT_B","EHMS_DEMO",true))){
    var result=store.recent("health-assessment","assetCode","A","generatedAt",null,null,12,Event.class);
    assertEquals(12,result.size());assertEquals(1198,result.get(0).value());assertTrue(result.stream().allMatch(v->v.assetCode().equals("A")));
    var range=store.recent("health-assessment","pointCode","P","generatedAt",start.plusSeconds(10),start.plusSeconds(12),10,Event.class);
    assertEquals(3,range.size());assertEquals(12,range.get(0).value());
   }
  }
  try(var p=ProjectContext.bind("OTHER")){assertTrue(store.recent("health-assessment","assetCode","A","generatedAt",null,null,12,Event.class).isEmpty());}
 }
 @Test void invalidJsonIdentifiersAndUnboundedLimitsAreRejected(){
  assertThrows(IllegalArgumentException.class,()->store.recent("health-assessment","assetCode'); DROP TABLE x;--","A","generatedAt",null,null,10,Event.class));
  assertThrows(IllegalArgumentException.class,()->store.recent("health-assessment","assetCode","A","payload",null,null,10,Event.class));
  assertThrows(IllegalArgumentException.class,()->store.recent("health-assessment","assetCode","A","generatedAt",null,null,10001,Event.class));
  assertThrows(IllegalArgumentException.class,()->store.recent("health-assessment","assetCode",null,"generatedAt",null,null,10,Event.class));
 }
 @Test @SuppressWarnings("unchecked") void openGaussQueryPushesScopeFieldTimeAndLimitToDatabase()throws Exception{
  var db=mock(JdbcTemplate.class);when(db.execute(any(ConnectionCallback.class))).thenReturn(false);
  when(db.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
  var nativeStore=new OpenGaussJsonStore(db,mapper,"ehm","aggregate_store",false);
  try(var p=ProjectContext.bind("PROJECT_B");var scope=EhmCentralScope.bind(new CentralAggregateIsolationTest().context("PROJECT_B","EHMS_DEMO",true))){
   nativeStore.recent("sample-telemetry","pointCode","P_'%", "sourceTimestamp",Instant.EPOCH,Instant.EPOCH.plusSeconds(10),20,Map.class);
  }
  var sql=ArgumentCaptor.forClass(String.class);var params=ArgumentCaptor.forClass(Object[].class);
  verify(db).query(sql.capture(),any(RowMapper.class),params.capture());
  assertTrue(sql.getValue().contains("EHMS_DEMO"));assertTrue(sql.getValue().contains("json_extract_path_text"));assertTrue(sql.getValue().endsWith("LIMIT ?"));
  assertFalse(sql.getValue().contains("P_'%"));assertEquals("P_'%",params.getValue()[2]);assertEquals(20,params.getValue()[5]);
 }
}
