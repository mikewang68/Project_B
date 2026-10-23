package com.bproject.safety.database;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Verifies the deployed schema; deliberately does not create missing columns. */
@EnabledIfEnvironmentVariable(named="SCS_TEST_JDBC_URL",matches=".+")
class RuntimeSchemaDatabaseTest {
  @Test void olderDatabaseHasRequiredCompatibleRuntimeColumns() throws Exception {
    Class.forName("org.postgresql.Driver");
    try(var connection=DriverManager.getConnection(System.getenv("SCS_TEST_JDBC_URL"),
        System.getenv("SCS_TEST_DB_USER"),System.getenv("SCS_TEST_DB_PASSWORD"))){
      connection.setReadOnly(true);connection.setAutoCommit(false);
      try{
        try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT current_database()")){
          assertTrue(result.next());String database=result.getString(1);
          assertTrue(database.startsWith("codex_public_candidate_")||database.startsWith("codex_unified_"),"Owned database required");
        }
        for(var entry:Map.of("sys_team","is_deleted","safety_area","is_deleted",
            "safety_fence","buffer_distance","safety_fence_version","buffer_distance").entrySet()){
          try(var query=connection.prepareStatement("SELECT data_type,is_nullable FROM information_schema.columns WHERE table_schema='safety' AND table_name=? AND column_name=?")){
            query.setString(1,entry.getKey());query.setString(2,entry.getValue());
            try(var result=query.executeQuery()){
              assertTrue(result.next(),entry.getKey()+" requires "+entry.getValue());
              assertEquals(entry.getValue().equals("is_deleted")?"boolean":"numeric",result.getString(1));
              assertEquals("NO",result.getString(2));assertFalse(result.next());
            }
          }
        }
      }finally{connection.rollback();}
    }
  }
}
