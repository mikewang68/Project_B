package com.bproject.safety.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = OpenGaussSpikeSupport.ENV_SPIKE_ENABLED, matches = "true")
@DisplayName("Spike 1: openGauss 基础连接与 Schema/对象只读核验")
class OpenGaussConnectionSpikeTest extends OpenGaussSpikeSupport {

    @Test
    @DisplayName("验证 openGauss 数据库版本、数据库名称、当前用户与时区编码")
    void testBasicConnectionAndMetadata() throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Version
            try (ResultSet rs = stmt.executeQuery("SELECT version()")) {
                assertThat(rs.next()).isTrue();
                String version = rs.getString(1);
                assertThat(version).isNotBlank();
                System.out.println("[SPIKE] openGauss version: " + version);
            }

            // Database
            try (ResultSet rs = stmt.executeQuery("SELECT current_database()")) {
                assertThat(rs.next()).isTrue();
                String db = rs.getString(1);
                assertThat(db).isEqualTo("b_project");
                System.out.println("[SPIKE] Current database: " + db);
            }

            // User
            try (ResultSet rs = stmt.executeQuery("SELECT current_user")) {
                assertThat(rs.next()).isTrue();
                String user = rs.getString(1);
                assertThat(user).isNotBlank();
                System.out.println("[SPIKE] Current user: " + user);
            }

            // Timezone
            try (ResultSet rs = stmt.executeQuery("SHOW timezone")) {
                assertThat(rs.next()).isTrue();
                String tz = rs.getString(1);
                System.out.println("[SPIKE] Server timezone: " + tz);
            }

            // Encoding
            try (ResultSet rs = stmt.executeQuery("SHOW server_encoding")) {
                assertThat(rs.next()).isTrue();
                String enc = rs.getString(1);
                System.out.println("[SPIKE] Server encoding: " + enc);
            }
        }
    }

    @Test
    @DisplayName("验证 safety schema 下的 25 张表与 21 个序列是否存在")
    void testSafetySchemaObjectsCount() throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Tables in safety schema
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT table_name FROM information_schema.tables " +
                    "WHERE table_schema = 'safety' AND table_type = 'BASE TABLE' ORDER BY table_name")) {
                while (rs.next()) {
                    tables.add(rs.getString("table_name"));
                }
            }
            System.out.println("[SPIKE] Found " + tables.size() + " tables in safety schema: " + tables);
            assertThat(tables).hasSize(25);

            // Sequences in safety schema
            List<String> sequences = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT sequence_name FROM information_schema.sequences " +
                    "WHERE sequence_schema = 'safety' ORDER BY sequence_name")) {
                while (rs.next()) {
                    sequences.add(rs.getString("sequence_name"));
                }
            }
            System.out.println("[SPIKE] Found " + sequences.size() + " sequences in safety schema: " + sequences);
            assertThat(sequences).hasSize(21);
        }
    }
}
