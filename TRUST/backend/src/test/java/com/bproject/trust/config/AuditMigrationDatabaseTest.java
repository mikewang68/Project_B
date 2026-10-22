package com.bproject.trust.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

/** Production migration/grants on a dedicated local compatibility DB, never a deployed schema. */
@EnabledIfEnvironmentVariable(named = "TRUST_LOG_DB_TEST", matches = "1")
class AuditMigrationDatabaseTest {
  @Test
  void repeatedMigrationRetainsAppendOnlyLogsAndLimitedWorkerUpdates() throws Exception {
    var migration = com.bproject.trust.support.TestDatabase.dataSource(true);
    for (int i = 0; i < 2; i++)
      try (var connection = migration.getConnection()) {
        IsolatedMigration.migrate(connection, System.getenv("TRUST_DB_USER"));
      }
    var db = new JdbcTemplate(com.bproject.trust.support.TestDatabase.dataSource(false));
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
                "UPDATE audit_evidence_read SET archive_state='PENDING',last_error=NULL WHERE"
                    + " 1=0"));
    assertDoesNotThrow(
        () ->
            db.execute("UPDATE audit_delivery_read SET state='PENDING',event_json=NULL WHERE 1=0"));
    assertDoesNotThrow(
        () -> db.execute("UPDATE audit_page_action_read SET result_json=NULL WHERE 1=0"));
    assertFalse(
        db.queryForObject(
            "SELECT has_schema_privilege(current_user,'trust_data','CREATE')", Boolean.class));
  }
}
