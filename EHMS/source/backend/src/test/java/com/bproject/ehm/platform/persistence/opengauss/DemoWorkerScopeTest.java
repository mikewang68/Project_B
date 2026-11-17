package com.bproject.ehm.platform.persistence.opengauss;
import com.bproject.ehm.simulation.SimulationRunScope;
import com.bproject.ehm.platform.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DemoWorkerScopeTest {
 @AfterEach void clearRequest(){RequestContextHolder.resetRequestAttributes();assertNull(SimulationRunScope.current());assertNull(EhmCentralScope.current());}
 @Test void dedicatedWorkerWritesOnlyDemoAndOrdinaryReadsRemainIsolated()throws Exception{
  var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
  var store=new OpenGaussJsonStore(jdbc,new ObjectMapper(),"ehm","aggregate_store",true);store.initialize();
  try(var project=ProjectContext.bind("PROJECT_B")){
   store.save("asset","LEGACY",Map.of("name","old"));
   try(var worker=SimulationRunScope.bindDemo()){
    assertEquals(0,store.count("asset"));store.save("asset","SAMPLE-LIVE-TEST",Map.of("name","demo"));
    assertEquals(1,store.count("asset"));assertNull(EhmCentralScope.current());
   }
   assertEquals(1,store.count("asset"));assertTrue(store.find("asset","SAMPLE-LIVE-TEST",Map.class).isEmpty());
   var contexts=new CentralAggregateIsolationTest();
   try(var scope=EhmCentralScope.bind(contexts.context("PROJECT_B","SHEN_TIE",false))){assertEquals(0,store.count("asset"));assertThrows(IllegalStateException.class,SimulationRunScope::bindDemo);}
   try(var scope=EhmCentralScope.bind(contexts.context("PROJECT_B","EHMS_DEMO",true))){assertEquals(1,store.count("asset"));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.save("asset","X",Map.of()));}
  }
 }
 @Test void projectRequestAndReusedThreadsCannotAcquireWorkerScope()throws Exception{
  try(var project=ProjectContext.bind("PROJECT_A")){assertThrows(IllegalStateException.class,SimulationRunScope::bindDemo);}
  try(var project=ProjectContext.bind("PROJECT_B")){
   RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
   assertThrows(IllegalStateException.class,SimulationRunScope::bindDemo);RequestContextHolder.resetRequestAttributes();
   try(var worker=SimulationRunScope.bindDemo()){
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    assertThrows(IllegalStateException.class,SimulationRunScope::current);RequestContextHolder.resetRequestAttributes();
   }
   assertNull(SimulationRunScope.current());
  }
 }
}
