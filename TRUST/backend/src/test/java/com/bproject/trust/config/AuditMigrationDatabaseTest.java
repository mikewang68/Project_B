package com.bproject.trust.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Production migration/grants on a dedicated local compatibility DB, never a deployed schema. */
@EnabledIfEnvironmentVariable(named = "TRUST_LOG_DB_TEST", matches = "1")
class AuditMigrationDatabaseTest {
  @Test
  void repeatedMigrationRetainsAppendOnlyLogsAndLimitedWorkerUpdates() throws Exception {
    String url = System.getenv("TRUST_LOG_DB_URL");
    if (!"jdbc:postgresql://127.0.0.1:25434/trust_audit_local_test".equals(url))
      throw new IllegalStateException("Only dedicated local audit test database allowed");
    var migration = new DriverManagerDataSource(url, "trust_local_test", "");
    var admin = new JdbcTemplate(migration);
    assertEquals(
        0,
        admin.queryForObject(
            "SELECT COUNT(*) FROM pg_namespace WHERE nspname='trust_data'", Integer.class),
        "Refuse to reuse or drop an existing schema");
    String role =
        "trust_iam_audit_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            + "_app";
    admin.execute("CREATE ROLE " + role + " LOGIN");
    try {
      admin.execute("CREATE SCHEMA trust_data");
      admin.execute("GRANT USAGE ON SCHEMA trust_data TO " + role);
      for (int i = 0; i < 2; i++) {
        try (var connection = migration.getConnection()) {
          IsolatedMigration.migrate(connection, role);
        }
      }
      var application = new DriverManagerDataSource(url, role, "");
      application.setSchema("trust_data");
      var db = new JdbcTemplate(application);
      assertEquals(
          1,
          db.update(
              "INSERT INTO"
                  + " audit_delivery(id,org_id,actor,action,object_id,outcome,source_kind,menu_id,method,path,summary,trace_id,occurred_at)"
                  + " VALUES(?,'UNVERIFIED_SECURITY',NULL,'LOGIN',NULL,'STARTED','REQUEST',NULL,'POST','/api/v1/iam/login',NULL,NULL,'2026-01-02T03:04:05Z')",
              UUID.randomUUID().toString()));
      for (String sql :
          new String[] {
            "UPDATE audit_evidence SET ciphertext='tamper'",
            "UPDATE audit_evidence SET payload_hash=repeat('a',64)",
            "DELETE FROM audit_evidence",
            "UPDATE audit_delivery SET actor='forged'",
            "DELETE FROM audit_delivery",
            "UPDATE audit_page_action SET actor='forged'",
            "DELETE FROM audit_page_action",
            "UPDATE audit_evidence_access SET outcome='SUCCESS'",
            "DELETE FROM audit_evidence_access",
            "SELECT * FROM databasechangelog"
          }) {
        var failure =
            assertThrows(
                org.springframework.dao.DataAccessException.class, () -> db.execute(sql), sql);
        assertEquals(
            "42501", ((java.sql.SQLException) failure.getMostSpecificCause()).getSQLState(), sql);
      }
      assertDoesNotThrow(
          () ->
              db.execute(
                  "UPDATE audit_evidence SET archive_state='PENDING',last_error=NULL WHERE 1=0"));
      assertDoesNotThrow(
          () -> db.execute("UPDATE audit_delivery SET state='PENDING',event_json=NULL WHERE 1=0"));
      assertDoesNotThrow(
          () -> db.execute("UPDATE audit_page_action SET result_json=NULL WHERE 1=0"));
      assertFalse(
          db.queryForObject(
              "SELECT has_schema_privilege(current_user,'trust_data','CREATE')", Boolean.class));
    } finally {
      admin.execute("DROP SCHEMA IF EXISTS trust_data CASCADE");
      admin.execute("DROP ROLE " + role);
    }
  }
}
