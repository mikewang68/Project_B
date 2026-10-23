package com.bdemo.iam.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bdemo.iam.permission.mapper.PermissionMapper;
import java.sql.DriverManager;
import java.util.List;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Uses connection-local temporary tables in an explicitly owned integration database. */
@EnabledIfEnvironmentVariable(named = "IAM_TEST_JDBC_URL", matches = ".+")
class PermissionMapperDatabaseTest {
  @Test void returnsCurrentCodesWhenPermissionIdsAreDifferent() throws Exception {
    try (var connection = DriverManager.getConnection(System.getenv("IAM_TEST_JDBC_URL"),
        System.getenv("IAM_TEST_DB_USER"), System.getenv("IAM_TEST_DB_PASSWORD"))) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        try (var result = statement.executeQuery("SELECT current_database()")) {
          assertTrue(result.next());
          String name = result.getString(1);
          assertTrue(name.startsWith("codex_unified_") || name.startsWith("codex_public_candidate_"),
              "Integration checks require an owned database");
        }
        statement.execute("SET LOCAL search_path TO pg_temp,public");
        statement.execute("CREATE TEMP TABLE iam_role(id varchar(64),status varchar(16),deleted smallint) ON COMMIT PRESERVE ROWS");
        statement.execute("CREATE TEMP TABLE iam_permission(id varchar(64),permission_code varchar(128),status varchar(16)) ON COMMIT PRESERVE ROWS");
        statement.execute("CREATE TEMP TABLE iam_role_permission(role_id varchar(64),permission_id varchar(64)) ON COMMIT PRESERVE ROWS");
        statement.execute("INSERT INTO iam_role VALUES('active-role','active',0),('disabled-role','disabled',0),('deleted-role','active',1)");
        statement.execute("INSERT INTO iam_permission VALUES('opaque-read-row','wms:master:read','active'),"
            + "('opaque-export-row','wms:export','disabled'),('other-row','trust:event:read','active')");
        statement.execute("INSERT INTO iam_role_permission VALUES('active-role','opaque-read-row'),"
            + "('active-role','opaque-export-row'),('disabled-role','other-row'),('deleted-role','other-row')");
        var configuration = new Configuration(new Environment("owned-integration",
            new JdbcTransactionFactory(), new UnpooledDataSource()));
        configuration.addMapper(PermissionMapper.class);
        var factory = new SqlSessionFactoryBuilder().build(configuration);
        try (var session = factory.openSession(connection)) {
          var mapper = session.getMapper(PermissionMapper.class);
          var roles = List.of("active-role", "disabled-role", "deleted-role");
          assertEquals(List.of("wms:master:read"), mapper.selectCodesByRoleIds(roles));
          statement.execute("UPDATE iam_permission SET status='disabled' WHERE id='opaque-read-row'");
          session.clearCache(); // Each protected HTTP request opens a fresh mapper session.
          assertTrue(mapper.selectCodesByRoleIds(roles).isEmpty());
          statement.execute("UPDATE iam_permission SET status='active' WHERE id='opaque-read-row'");
          statement.execute("UPDATE iam_role SET status='disabled' WHERE id='active-role'");
          session.clearCache();
          assertTrue(mapper.selectCodesByRoleIds(roles).isEmpty());
          connection.rollback();
        }
      }
    }
  }
}
