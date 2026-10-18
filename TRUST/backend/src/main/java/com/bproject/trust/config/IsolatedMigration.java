package com.bproject.trust.config;

import java.sql.DriverManager;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/** Dedicated migration entry point; the running application has no DDL permission. */
public final class IsolatedMigration {
  public static void main(String[] args) throws Exception {
    String url = System.getenv("TRUST_DB_URL");
    var instance =
        IdentityInstance.read(
            System.getenv("TRUST_INSTANCE_ALLOWLIST"), System.getenv("TRUST_INSTANCE"));
    if (!instance.databaseUrl().equals(url))
      throw new IllegalArgumentException("Refusing non-dedicated database");
    try (var connection =
        DriverManager.getConnection(
            url, instance.migrationRole(), System.getenv("TRUST_MIGRATE_PASSWORD"))) {
      migrate(connection, instance.appRole());
    }
  }

  static void migrate(java.sql.Connection connection, String app) throws Exception {
    if (app == null || !app.matches("trust_iam_[a-z0-9_]{1,24}_app"))
      throw new IllegalArgumentException("Invalid application role");
    var database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection));
    database.setDefaultSchemaName("trust_data");
    database.setLiquibaseSchemaName("trust_data");
    try (var migrations =
        new Liquibase("db/changelog.xml", new ClassLoaderResourceAccessor(), database)) {
      migrations.update(new liquibase.Contexts(), new liquibase.LabelExpression());
      try (var grants = connection.createStatement()) {
        grants.execute(
            "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA trust_data TO " + app);
        grants.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA trust_data TO " + app);
        grants.execute(
            "REVOKE ALL ON"
                + " trust_data.databasechangelog,trust_data.databasechangeloglock FROM "
                + app);
        grants.execute(
            "REVOKE UPDATE,DELETE ON"
                + " trust_data.event_signing_context,trust_data.signing_attempts,trust_data.identity_audit"
                + " FROM "
                + app);
        grants.execute(
            "GRANT UPDATE(state,ledger_identity,block_number,last_error,resolved_at) ON"
                + " trust_data.signing_attempts TO "
                + app);
        grants.execute(
            "REVOKE UPDATE,DELETE ON"
                + " trust_data.generic_event_data,trust_data.event_source_routes FROM "
                + app);
        grants.execute(
            "GRANT UPDATE(original_cid,last_error_code) ON"
                + " trust_data.generic_event_data TO "
                + app);
        grants.execute("REVOKE UPDATE,DELETE ON trust_data.audit_evidence FROM " + app);
        grants.execute(
            "GRANT"
                + " UPDATE(archive_cid,archive_state,chain_event_id,attempts,next_at,lease_until,lease_token,last_error)"
                + " ON trust_data.audit_evidence TO "
                + app);
        grants.execute(
            "REVOKE UPDATE,DELETE ON trust_data.audit_delivery,trust_data.audit_page_action FROM "
                + app);
        grants.execute(
            "GRANT UPDATE(event_json,state,attempts,next_at,last_error) ON"
                + " trust_data.audit_delivery TO "
                + app);
        grants.execute("GRANT UPDATE(result_json) ON trust_data.audit_page_action TO " + app);
        grants.execute("REVOKE UPDATE,DELETE ON trust_data.audit_evidence_access FROM " + app);
      }
    }
  }
}
