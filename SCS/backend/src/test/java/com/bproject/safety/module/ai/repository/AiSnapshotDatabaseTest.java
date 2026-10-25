package com.bproject.safety.module.ai.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Read-only checks against an owned restored dataset, including large event histories. */
@EnabledIfEnvironmentVariable(named="SCS_TEST_JDBC_URL",matches=".+")
class AiSnapshotDatabaseTest {
  @Test void recentEventsDoNotMaterializeTheFullHistory() throws Exception {
    Class.forName("org.postgresql.Driver");
    try(var connection=DriverManager.getConnection(System.getenv("SCS_TEST_JDBC_URL"),
        System.getenv("SCS_TEST_DB_USER"),System.getenv("SCS_TEST_DB_PASSWORD"))){
      connection.setReadOnly(true);connection.setAutoCommit(false);
      try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT current_database()")){
        assertTrue(result.next());String database=result.getString(1);
        assertTrue(database.startsWith("codex_public_candidate_")||database.startsWith("codex_unified_"),"Owned database required");
      }
      try{
        var repository=new JdbcAiEventRepository(new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection,true)),
            new ObjectMapper(),mock(DemoMasterData.class),Clock.systemUTC());
        var counts=repository.counts();var recent=repository.findRecent(7);
        assertEquals(repository.count(),counts.total());
        assertEquals(Math.min(7L,counts.total()),recent.size());
        assertTrue(counts.pending()+counts.confirmed()+counts.falsePositive()<=counts.total());
        assertTrue(recent.stream().noneMatch(event->"CANCELLED".equals(event.statusCode)));
        for(int i=1;i<recent.size();i++)assertFalse(recent.get(i).occurredAt.isAfter(recent.get(i-1).occurredAt));
        var start=OffsetDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        assertTrue(repository.countOccurredBetween(start,start.plusDays(1))<=counts.total());
        assertTrue(repository.distinctAreas().stream().noneMatch(String::isBlank));
        assertTrue(repository.distinctCameras().stream().noneMatch(String::isBlank));
      }finally{connection.rollback();}
    }
  }
}
