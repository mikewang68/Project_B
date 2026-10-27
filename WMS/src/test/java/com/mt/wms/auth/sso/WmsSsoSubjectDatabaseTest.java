package com.mt.wms.auth.sso;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Exercises the real mapping query and upgrade using connection-local Gauss tables. */
@EnabledIfEnvironmentVariable(named = "WMS_TEST_JDBC_URL", matches = ".+")
class WmsSsoSubjectDatabaseTest {
  @Test void nullableZoneDoesNotHideAnActiveCompanyBinding() throws Exception {
    Class.forName("org.postgresql.Driver");
    try (var connection = DriverManager.getConnection(System.getenv("WMS_TEST_JDBC_URL"),
        System.getenv("WMS_TEST_DB_USER"), System.getenv("WMS_TEST_DB_PASSWORD"))) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        try (var rows = statement.executeQuery("SELECT current_database()")) {
          assertTrue(rows.next());
          assertTrue(rows.getString(1).startsWith("codex_public_candidate_")
              || rows.getString(1).startsWith("codex_unified_"), "Owned database required");
        }
        statement.execute("SET LOCAL search_path TO pg_temp,public");
        statement.execute("CREATE TEMP TABLE auth_company(id bigint,code varchar(64),status varchar(16)) ON COMMIT PRESERVE ROWS");
        statement.execute("CREATE TEMP TABLE auth_user(id bigint,company_id bigint,username varchar(64),status varchar(16),login_enabled boolean,password_change_required boolean,deleted_at timestamp) ON COMMIT PRESERVE ROWS");
        statement.execute("CREATE TEMP TABLE wms_sso_subject_map(iam_subject varchar(64),audience varchar(16),local_user_id bigint,company_code varchar(64),zone_code varchar(64) NOT NULL,enabled smallint) ON COMMIT PRESERVE ROWS");
        String upgrade = Files.readString(Path.of("db/sso/002-gauss-empty-zone.sql"))
            .replaceAll("(?m)^\\s*(?:BEGIN|COMMIT);\\s*$", "");
        statement.execute(upgrade);
        statement.execute("INSERT INTO auth_company VALUES(1,'tenant-a','ENABLED')");
        statement.execute("INSERT INTO auth_user VALUES(1,1,'native-user','ENABLED',true,false,NULL)");
        statement.execute("INSERT INTO wms_sso_subject_map VALUES('opaque-subject','wms',1,'tenant-a','',1)");
        var repository = new WmsSsoSubjectRepository(JdbcClient.create(new SingleConnectionDataSource(connection, true)));
        var binding = repository.findActive("opaque-subject").orElseThrow();
        assertEquals("native-user", binding.localUsername());
        assertEquals("tenant-a", binding.companyCode());
        assertTrue(binding.zoneCode() == null || binding.zoneCode().isBlank());
        statement.execute("UPDATE wms_sso_subject_map SET company_code='tenant-b'");
        assertTrue(repository.findActive("opaque-subject").isEmpty());
        statement.execute("UPDATE wms_sso_subject_map SET company_code='tenant-a',enabled=0");
        assertTrue(repository.findActive("opaque-subject").isEmpty());
        statement.execute("UPDATE wms_sso_subject_map SET enabled=1");
        statement.execute("UPDATE auth_user SET deleted_at=CURRENT_TIMESTAMP");
        assertTrue(repository.findActive("opaque-subject").isEmpty());
        assertTrue(repository.findActive("unknown-subject").isEmpty());
      } finally {
        connection.rollback();
      }
    }
  }
}
