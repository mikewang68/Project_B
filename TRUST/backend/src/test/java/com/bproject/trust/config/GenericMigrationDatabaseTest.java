package com.bproject.trust.config;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real JDBC/Liquibase tests; the test operator records PostgreSQL vs openGauss separately. */
@EnabledIfEnvironmentVariable(named = "TRUST_GENERIC_DB_TEST", matches = "1")
class GenericMigrationDatabaseTest {
  static JdbcTemplate db;

  static DriverManagerDataSource dataSource(boolean migration) {
    return com.bproject.trust.support.TestDatabase.dataSource(migration);
  }

  @BeforeAll
  static void migrate() throws Exception {
    // Exercise production migrations and grants; the compatibility DB is not a deployed
    // instance.
    for (int i = 0; i < 2; i++)
      try (var c = dataSource(true).getConnection()) {
        IsolatedMigration.migrate(c, System.getenv("TRUST_DB_USER"));
      }
    db = new JdbcTemplate(dataSource(false));
  }

  @Test
  void applicationIsRestrictedAndMigrationIsRepeatable() {
    assertFalse(
        db.queryForObject(
            "SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE"
                + " rolname=current_user",
            Boolean.class));
    assertFalse(
        db.queryForObject(
            "SELECT has_schema_privilege(current_user,'trust_data','CREATE')", Boolean.class));
    for (String sql :
        new String[] {
          "UPDATE generic_event_data SET original_utf8='tamper'",
          "UPDATE generic_event_data SET original_sha256=repeat('a',64)",
          "UPDATE generic_event_data SET original_size_bytes=1",
          "DELETE FROM generic_event_data",
          "UPDATE event_source_routes SET source_id='takeover'",
          "DELETE FROM event_source_routes",
          "SELECT * FROM databasechangelog"
        })
      assertThrows(org.springframework.dao.DataAccessException.class, () -> db.execute(sql), sql);
    // Column grants are sufficient for archival/retry without changing the original.
    assertDoesNotThrow(
        () ->
            db.execute(
                "UPDATE generic_event_data SET"
                    + " original_cid=NULL,last_error_code=NULL WHERE 1=0"));
    assertEquals(
        7,
        new JdbcTemplate(dataSource(true))
            .queryForObject("SELECT COUNT(*) FROM databasechangelog", Integer.class));
  }

  @Test
  void legacySnapshotSurvivesUpgradeAndConditionalBatchConstraintRemains() throws Exception {
    String schema = "generic_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection c = dataSource(true).getConnection()) {
      try (var s = c.createStatement()) {
        s.execute("CREATE SCHEMA " + schema);
      }
      c.setSchema(schema);
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      database.setDefaultSchemaName(schema);
      database.setLiquibaseSchemaName(schema);
      try (var migration =
          new Liquibase("db/changelog.xml", new ClassLoaderResourceAccessor(), database)) {
        migration.update(4, new liquibase.Contexts(), new liquibase.LabelExpression());
        String id = UUID.randomUUID().toString();
        try (var s =
            c.prepareStatement(
                "INSERT INTO"
                    + " events(id,org_id,source_system,source_event_id,event_type,batch_id,object_id,occurred_at,submitted_by,request_json,canonical_json,event_sha256,root_id,version)"
                    + " VALUES(?,'OLD','OLD','one','WAREHOUSE_IN','batch','object',CURRENT_TIMESTAMP,'old','"
                    + " {  } ',' {  } ',repeat('a',64),?,1)")) {
          s.setString(1, id);
          s.setString(2, id);
          s.executeUpdate();
        }
        migration.update(new liquibase.Contexts(), new liquibase.LabelExpression());
        migration.update(new liquibase.Contexts(), new liquibase.LabelExpression());
        c.commit();
        c.setAutoCommit(true);
        try (var s = c.createStatement();
            var r = s.executeQuery("SELECT canonical_json,record_kind FROM events")) {
          assertTrue(r.next());
          assertEquals(" {  } ", r.getString(1));
          assertEquals("LEGACY_V1", r.getString(2));
        }
        try (var s = c.createStatement()) {
          assertThrows(
              SQLException.class, () -> s.executeUpdate("UPDATE events SET batch_id=NULL"));
          assertThrows(
              SQLException.class,
              () -> s.executeUpdate("UPDATE events SET record_kind='GENERIC_V1'"));
          assertEquals(
              1,
              s.executeUpdate(
                  "UPDATE events SET"
                      + " record_kind='GENERIC_V1',batch_id=NULL,signing_required=TRUE"));
          assertThrows(
              SQLException.class,
              () -> s.executeUpdate("UPDATE events SET signing_required=FALSE"));
          try (var r = s.executeQuery("SELECT record_kind,source_id FROM event_source_routes")) {
            assertTrue(r.next());
            assertEquals("LEGACY_V1", r.getString(1));
            assertNull(r.getString(2));
          }
          s.execute("SET search_path TO trust_data");
          s.execute("DROP SCHEMA " + schema + " CASCADE");
        }
      }
    }
  }
}
