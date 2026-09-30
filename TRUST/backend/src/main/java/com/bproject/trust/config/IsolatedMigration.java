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
      var database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      database.setDefaultSchemaName("trust_data");
      database.setLiquibaseSchemaName("trust_data");
      try (var migrations =
          new Liquibase("db/changelog.xml", new ClassLoaderResourceAccessor(), database)) {
        migrations.update(new liquibase.Contexts(), new liquibase.LabelExpression());
        try (var grants = connection.createStatement()) {
          String app = instance.appRole();
          grants.execute(
              "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA trust_data TO " + app);
          grants.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA trust_data TO " + app);
          grants.execute(
              "REVOKE ALL ON trust_data.databasechangelog,trust_data.databasechangeloglock FROM "
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
        }
      }
    }
  }
}
