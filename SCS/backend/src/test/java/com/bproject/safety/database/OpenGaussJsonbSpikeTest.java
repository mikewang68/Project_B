package com.bproject.safety.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = OpenGaussSpikeSupport.ENV_SPIKE_ENABLED, matches = "true")
@DisplayName("Spike 2: openGauss JSONB 字段读写兼容性实机测试")
class OpenGaussJsonbSpikeTest extends OpenGaussSpikeSupport {

    private static final String SAMPLE_POLYGON_GEOJSON =
            "{\"type\":\"Polygon\",\"coordinates\":[[[1.0,1.0],[2.0,1.0],[2.0,2.0],[1.0,2.0],[1.0,1.0]]]}";

    private static final String TEST_FENCE_CODE = "FENCE-SPIKE-TEST-JSONB";

    @Test
    @DisplayName("测试以 SQL 字面量和 PGobject 写入 JSONB 字段并校验读取结果")
    void testJsonbInsertAndSelect() throws Exception {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Clean previous spike row if exists
                try (PreparedStatement cleanStmt = conn.prepareStatement(
                        "DELETE FROM safety.safety_fence_version WHERE fence_id IN (SELECT id FROM safety.safety_fence WHERE fence_code = ?)")) {
                    cleanStmt.setString(1, TEST_FENCE_CODE);
                    cleanStmt.executeUpdate();
                }
                try (PreparedStatement cleanStmt = conn.prepareStatement(
                        "DELETE FROM safety.safety_fence WHERE fence_code = ?")) {
                    cleanStmt.setString(1, TEST_FENCE_CODE);
                    cleanStmt.executeUpdate();
                }

                // 2. Insert parent fence
                long parentFenceId = -1;
                String insertParentSql = "INSERT INTO safety.safety_fence " +
                        "(fence_code, fence_name, status_code) VALUES (?, ?, 'DRAFT') RETURNING id";
                try (PreparedStatement ps = conn.prepareStatement(insertParentSql)) {
                    ps.setString(1, TEST_FENCE_CODE);
                    ps.setString(2, "Spike Test Fence");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        parentFenceId = rs.getLong(1);
                        assertThat(parentFenceId).isGreaterThan(0);
                    }
                }

                // 3. Insert child fence version with PGobject (Method A: Recommended for openGauss-jdbc)
                String insertVersionSql = "INSERT INTO safety.safety_fence_version " +
                        "(fence_id, version_no, risk_level_code, status_code, polygon) " +
                        "VALUES (?, 'v1.0', 'WARNING', 'DRAFT', ?) RETURNING id";

                long generatedVersionId = -1;
                try (PreparedStatement ps = conn.prepareStatement(insertVersionSql)) {
                    ps.setLong(1, parentFenceId);

                    PGobject jsonObject = new PGobject();
                    jsonObject.setType("jsonb");
                    jsonObject.setValue(SAMPLE_POLYGON_GEOJSON);
                    ps.setObject(2, jsonObject);

                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        generatedVersionId = rs.getLong(1);
                        assertThat(generatedVersionId).isGreaterThan(0);
                    }
                }

                // 4. Select back and verify
                try (PreparedStatement selectStmt = conn.prepareStatement(
                        "SELECT polygon, polygon::text AS geojson_text FROM safety.safety_fence_version WHERE id = ?")) {
                    selectStmt.setLong(1, generatedVersionId);
                    try (ResultSet rs = selectStmt.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        String readText = rs.getString("geojson_text");
                        assertThat(readText).contains("\"Polygon\"");
                        assertThat(readText).contains("coordinates");
                        System.out.println("[SPIKE] JSONB read-back successfully: " + readText);
                    }
                }

                // 5. Test Method B: setObject with Types.OTHER
                String updateSql = "UPDATE safety.safety_fence_version SET polygon = ?::jsonb WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setObject(1, SAMPLE_POLYGON_GEOJSON, Types.OTHER);
                    ps.setLong(2, generatedVersionId);
                    int updated = ps.executeUpdate();
                    assertThat(updated).isEqualTo(1);
                    System.out.println("[SPIKE] JSONB Types.OTHER parameter binding passed.");
                }

            } finally {
                conn.rollback(); // Always rollback test data
            }
        }
    }
}
