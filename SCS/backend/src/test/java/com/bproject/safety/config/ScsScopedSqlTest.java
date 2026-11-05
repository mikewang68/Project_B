package com.bproject.safety.config;

import com.bproject.iam.context.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;

class ScsScopedSqlTest {
  NamedParameterJdbcTemplate raw,scoped;
  UserContext context(boolean readonly,ScopeSql.Mode mode,String level){
    var t=new ScopeTriple("PROJECT_B","SHEN_TIE","SCS");var scopes=mode==ScopeSql.Mode.DENY?List.<ScopeTriple>of():List.of(t);var f=ScopeSql.build(mode,scopes);
    return new UserContext(2,"u","u","u","u",level,readonly,"SCS","PROJECT_B","SHEN_TIE","SCS",mode,List.of(t),scopes,Set.of("job"),Set.of("SCS:alarms"),Map.of("SCS:alarms",Set.of("VIEW","EDIT")),Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
  }
  void bind(UserContext context){var request=new MockHttpServletRequest();ScsCentralScope.bind(request,context);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));}
  @BeforeEach void setup(){
    var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
    raw=new NamedParameterJdbcTemplate(ds);scoped=new ScsScopedJdbcTemplate(raw);
    raw.getJdbcTemplate().execute("CREATE SCHEMA safety");
    for(String table:List.of("safety_alert","ai_event")) {
      raw.getJdbcTemplate().execute("CREATE TABLE safety."+table+"(id int primary key, project_id varchar(64), region_id varchar(64), subsystem_code varchar(32), title varchar(100))");
      raw.getJdbcTemplate().execute("INSERT INTO safety."+table+" VALUES (1,'PROJECT_B','SHEN_TIE','SCS','own'),(2,'PROJECT_B','HA_TIE','SCS','other region'),(3,'PROJECT_A','SHEN_TIE','SCS','other project'),(4,NULL,NULL,NULL,'unknown')");
    }
    bind(context(false,ScopeSql.Mode.SCOPED,"L4_OPERATOR"));
  }
  @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();}
  int count(String sql){return scoped.queryForObject(sql,Map.of(),Integer.class);}
  @Test void whereOrCannotEscapeAndUnknownRowsStayHidden(){
    assertEquals(1,count("SELECT count(*) FROM safety.safety_alert WHERE id=2 OR 1=1"));
    assertEquals(List.of(1),scoped.queryForList("SELECT id FROM safety.safety_alert ORDER BY id",Map.of(),Integer.class));
  }
  @Test void cteJoinRepeatedSourcesAndSubqueriesAreAllScoped(){
    assertEquals(1,count("WITH hits AS (SELECT id FROM safety.safety_alert) SELECT count(*) FROM hits"));
    assertEquals(1,count("SELECT count(*) FROM safety.safety_alert a JOIN safety.ai_event e ON a.id=e.id"));
    assertEquals(1,count("SELECT count(*) FROM safety.safety_alert a JOIN safety.safety_alert sa ON a.id=sa.id"));
    assertEquals(1,count("SELECT count(*) FROM safety.ai_event e WHERE EXISTS (SELECT 1 FROM safety.safety_alert a WHERE a.id=e.id)"));
  }
  @Test void mixedCaseQuotedIdentifiersAndExplicitAliasesWork(){
    assertEquals(1,count("sElEcT count(*) FrOm safety.safety_alert AS a"));
    String sql=ScsScopedSql.apply("SELECT a.id FROM \"safety\".\"safety_alert\" AS a",ScsCentralScope.current());
    assertTrue(sql.contains("(SELECT * FROM safety.safety_alert o"));assertTrue(sql.endsWith(" AS a"));
    assertThrows(IllegalArgumentException.class,()->count("SELECT count(*) FROM safety.safety_alert arbitrary_alias"));
  }
  @Test void literalCommentAndDollarQuoteContentsAreNeverTreatedAsSqlSources(){
    String q="SELECT 'FROM safety.safety_alert UPDATE' AS label, count(*) FROM safety.safety_alert /* JOIN safety.ai_event e */ -- FROM safety.ai_event\n";
    var rows=scoped.queryForList(q,Map.of());assertEquals(1,rows.size());assertEquals("FROM safety.safety_alert UPDATE",rows.get(0).get("LABEL"));
    String dollar="SELECT $$FROM safety.safety_alert$$ FROM safety.ai_event";
    assertTrue(ScsScopedSql.apply(dollar,ScsCentralScope.current()).startsWith("SELECT $$FROM safety.safety_alert$$ FROM (SELECT"));
    assertTrue(ScsScopedSql.apply("SELECT 1 /* nested /* FROM safety.ai_event */ comment */",ScsCentralScope.current()).contains("nested /* FROM"));
    String escaped="SELECT E'quoted\\' FROM safety.safety_alert' AS label FROM safety.ai_event";
    assertTrue(ScsScopedSql.apply(escaped,ScsCentralScope.current()).startsWith("SELECT E'quoted\\' FROM safety.safety_alert' AS label FROM (SELECT"));
  }
  @Test void updateChecksScopeAtomicallyAndGroupsExistingOr(){
    assertEquals(0,scoped.update("UPDATE safety.safety_alert SET title=:v WHERE id=2 OR id=3",Map.of("v","bad")));
    assertEquals(1,scoped.update("UPDATE safety.safety_alert SET title=:v WHERE id=1 OR id=2",Map.of("v","good")));
    assertEquals("other region",raw.queryForObject("SELECT title FROM safety.safety_alert WHERE id=2",Map.of(),String.class));
    assertEquals("good",raw.queryForObject("SELECT title FROM safety.safety_alert WHERE id=1",Map.of(),String.class));
    assertThrows(IllegalArgumentException.class,()->scoped.update("UPDATE safety.safety_alert SET title='unsafe'",Map.of()));
    assertThrows(IllegalArgumentException.class,()->scoped.update("DELETE FROM safety.safety_alert WHERE id=1",Map.of()));
    assertEquals(0,scoped.update("UPDATE safety.safety_alert SET title='bad' WHERE id=2 -- original predicate comment",Map.of()));
    assertThrows(IllegalArgumentException.class,()->scoped.update("UPDATE safety.safety_alert SET title='bad' WHERE id=1; UPDATE safety.ai_event SET title='bad' WHERE id=2",Map.of()));
  }
  @Test void readonlyAndDeniedContextsCannotWriteOrExposeFallbackRows(){
    bind(context(true,ScopeSql.Mode.SCOPED,"L1_READONLY"));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->scoped.update("UPDATE safety.safety_alert SET title='bad' WHERE id=1",Map.of()));
    bind(context(false,ScopeSql.Mode.DENY,"L4_OPERATOR"));assertEquals(0,count("SELECT count(*) FROM safety.safety_alert"));
    bind(null);assertEquals(4,count("SELECT count(*) FROM safety.safety_alert"));
  }
  @Test void globalModelCatalogIsReadableButConfigurationRequiresL1(){
    assertEquals("SELECT * FROM safety.ai_model",ScsScopedSql.apply("SELECT * FROM safety.ai_model",ScsCentralScope.current()));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->ScsScopedSql.apply("UPDATE safety.ai_model SET is_selected=true WHERE model_code='m'",ScsCentralScope.current()));
    assertEquals("UPDATE safety.ai_model SET is_selected=true WHERE model_code='m'",ScsScopedSql.apply("UPDATE safety.ai_model SET is_selected=true WHERE model_code='m'",context(false,ScopeSql.Mode.GLOBAL,"L1_SUPER")));
  }
  @Test void mutatingCteAndUnclosedLexicalConstructsAreRejected(){
    assertThrows(IllegalArgumentException.class,()->ScsScopedSql.apply("WITH x AS (DELETE FROM safety.safety_alert RETURNING id) SELECT * FROM x",ScsCentralScope.current()));
    assertThrows(IllegalArgumentException.class,()->ScsScopedSql.apply("SELECT 'unfinished FROM safety.ai_event",ScsCentralScope.current()));
  }
  @Test void machineReadsAndAtomicWritesOnlyCompletelyUnassignedRows(){
    var request=new MockHttpServletRequest();request.setAttribute("SCS_LEGACY_MACHINE_INGRESS",true);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    raw.getJdbcTemplate().execute("INSERT INTO safety.safety_alert VALUES(5,'PROJECT_B',NULL,NULL,'partial project'),(6,NULL,'SHEN_TIE',NULL,'partial region'),(7,NULL,NULL,'SCS','partial system')");
    assertEquals(List.of(4),scoped.queryForList("SELECT id FROM safety.safety_alert WHERE id=1 OR 1=1 ORDER BY id",Map.of(),Integer.class));
    assertEquals(1,scoped.update("UPDATE safety.safety_alert SET title='machine' WHERE id=1 OR 1=1",Map.of()));
    assertEquals("own",raw.queryForObject("SELECT title FROM safety.safety_alert WHERE id=1",Map.of(),String.class));
    assertEquals("partial project",raw.queryForObject("SELECT title FROM safety.safety_alert WHERE id=5",Map.of(),String.class));
    raw.getJdbcTemplate().execute("UPDATE safety.safety_alert SET region_id='SHEN_TIE' WHERE id=4");
    assertEquals(0,scoped.update("UPDATE safety.safety_alert SET title='race' WHERE id=4",Map.of()));
  }
  @Test void machineCachedIdentityGuardRejectsAnyOwnershipIncludingPartial(){
    var request=new MockHttpServletRequest();request.setAttribute("SCS_LEGACY_MACHINE_INGRESS",true);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    raw.getJdbcTemplate().execute("CREATE TABLE safety.scope_runtime_ownership(resource_key varchar(128),project_id varchar(64),region_id varchar(64),subsystem_code varchar(32))");
    raw.getJdbcTemplate().execute("INSERT INTO safety.scope_runtime_ownership VALUES('UWB:owned','PROJECT_B','SHEN_TIE','SCS'),('UWB:partial',NULL,NULL,'SCS')");
    var refs=new ScsScopedReferenceData(raw);
    assertFalse(refs.legacyIngestAllowed(ScsCentralScope.Root.RUNTIME,"UWB:owned"));
    assertFalse(refs.legacyIngestAllowed(ScsCentralScope.Root.RUNTIME,"UWB:partial"));
    assertTrue(refs.legacyIngestAllowed(ScsCentralScope.Root.RUNTIME,"UWB:old-stream"));
    assertFalse(new ScsScopedReferenceData(null).legacyIngestAllowed(ScsCentralScope.Root.RUNTIME,"UWB:old-stream"));
  }
}
