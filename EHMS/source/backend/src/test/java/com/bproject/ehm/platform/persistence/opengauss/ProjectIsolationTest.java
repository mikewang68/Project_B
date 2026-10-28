package com.bproject.ehm.platform.persistence.opengauss;

import com.bproject.ehm.platform.security.ProjectContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class ProjectIsolationTest {
  @Test void identicalBusinessIdsDoNotMixAcrossProjects()throws Exception{
    var ds=new DriverManagerDataSource("jdbc:h2:mem:project-isolation;DB_CLOSE_DELAY=-1","sa","");
    var store=new OpenGaussJsonStore(new JdbcTemplate(ds),new ObjectMapper(),"scope_test","aggregate_store",true);store.initialize();
    try(var ignored=ProjectContext.bind("PROJECT_B")){
      store.save("asset","SAME-ID",Map.of("name","B equipment"));
      store.insertIfAbsent("asset","ONLY-B",Map.of("name","B only"));
    }
    try(var ignored=ProjectContext.bind("PROJECT_C")){
      assertTrue(store.find("asset","SAME-ID",Map.class).isEmpty());assertEquals(0,store.count("asset"));
      store.save("asset","SAME-ID",Map.of("name","C equipment"));
      assertEquals("C equipment",store.find("asset","SAME-ID",Map.class).orElseThrow().get("name"));
      assertEquals(1,store.findAll("asset",Map.class).size());
      assertTrue(store.find("asset","ONLY-B",Map.class).isEmpty());
    }
    try(var ignored=ProjectContext.bind("PROJECT_B")){
      assertEquals("B equipment",store.find("asset","SAME-ID",Map.class).orElseThrow().get("name"));assertEquals(2,store.count("asset"));
    }
    assertEquals(0,store.count("asset")); // Context is cleared; unassigned history is separate.
  }
  @Test void nestedScopesRestoreAndDoNotAcceptSqlIdentifiers()throws Exception{
    try(var outer=ProjectContext.bind("PROJECT_B")){
      try(var inner=ProjectContext.bind("PROJECT_C")){assertEquals("PROJECT_C",ProjectContext.require("UNASSIGNED"));}
      assertEquals("PROJECT_B",ProjectContext.require("UNASSIGNED"));
    }
    assertEquals("UNASSIGNED",ProjectContext.require("UNASSIGNED"));
    assertThrows(IllegalStateException.class,()->ProjectContext.bind("B' OR 1=1"));
  }
}
