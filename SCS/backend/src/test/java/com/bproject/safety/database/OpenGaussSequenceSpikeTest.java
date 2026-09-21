package com.bproject.safety.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = OpenGaussSpikeSupport.ENV_SPIKE_ENABLED, matches = "true")
@DisplayName("Spike 4: openGauss Sequence 自增与数据库完整性约束实机测试")
class OpenGaussSequenceSpikeTest extends OpenGaussSpikeSupport {

    @Test
    @DisplayName("测试 Sequence 默认自增与 RETURNING 及 Generated Keys 返回行为")
    void testSequenceDefaultAndReturning() throws Exception {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Test 1: sys_team with RETURNING id
                String sql1 = "INSERT INTO safety.sys_team (team_code, team_name) VALUES (?, ?) RETURNING id";
                long teamId = -1;
                try (PreparedStatement ps = conn.prepareStatement(sql1)) {
                    ps.setString(1, "TEAM-SPIKE-SEQ-1");
                    ps.setString(2, "Sequence Test Team");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        teamId = rs.getLong(1);
                        assertThat(teamId).isGreaterThanOrEqualTo(1000); // Sequence starts at 1000
                        System.out.println("[SPIKE] sys_team generated id: " + teamId);
                    }
                }

                // Test 2: getGeneratedKeys method
                String sql2 = "INSERT INTO safety.safety_area (area_code, area_name) VALUES (?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql2, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, "AREA-SPIKE-SEQ-1");
                    ps.setString(2, "Sequence Test Area");
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        assertThat(rs.next()).isTrue();
                        long areaId = rs.getLong(1);
                        assertThat(areaId).isGreaterThanOrEqualTo(1000);
                        System.out.println("[SPIKE] safety_area generated key: " + areaId);
                    }
                }

                // Test 3: UUID PK for safety_alert
                UUID alertId = UUID.randomUUID();
                String sql3 = "INSERT INTO safety.safety_alert " +
                        "(id, alert_no, title, risk_level_code, status_code, source, occurred_at) " +
                        "VALUES (?, ?, 'Spike Alert', 'SEVERE', 'PENDING_ASSIGNMENT', 'COLLISION', now())";
                try (PreparedStatement ps = conn.prepareStatement(sql3)) {
                    ps.setObject(1, alertId);
                    ps.setString(2, "ALM-SPIKE-001");
                    int rows = ps.executeUpdate();
                    assertThat(rows).isEqualTo(1);
                    System.out.println("[SPIKE] safety_alert UUID PK inserted successfully: " + alertId);
                }

            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    @DisplayName("测试 UNIQUE、FOREIGN KEY、NOT NULL 完整性约束拦截")
    void testDatabaseConstraints() throws Exception {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Insert baseline row
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO safety.sys_team (team_code, team_name) VALUES ('TEAM-SPIKE-UQ', 'UQ Test Team')")) {
                    ps.executeUpdate();
                }

                // 1. UNIQUE constraint violation on team_code
                assertThatThrownBy(() -> {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO safety.sys_team (team_code, team_name) VALUES ('TEAM-SPIKE-UQ', 'Duplicate Team')")) {
                        ps.executeUpdate();
                    }
                }).isInstanceOf(SQLException.class)
                  .satisfies(e -> {
                      SQLException se = (SQLException) e;
                      System.out.println("[SPIKE] UNIQUE violation caught: SQLSTATE=" + se.getSQLState() + " msg=" + se.getMessage());
                      assertThat(se.getSQLState()).isEqualTo("23505"); // unique_violation
                  });

                // Rollback sub-error to resume transaction in openGauss
                conn.rollback();
                conn.setAutoCommit(false);

                // 2. NOT NULL constraint violation (missing team_name)
                assertThatThrownBy(() -> {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO safety.sys_team (team_code, team_name) VALUES ('TEAM-SPIKE-NN', NULL)")) {
                        ps.executeUpdate();
                    }
                }).isInstanceOf(SQLException.class)
                  .satisfies(e -> {
                      SQLException se = (SQLException) e;
                      System.out.println("[SPIKE] NOT NULL violation caught: SQLSTATE=" + se.getSQLState() + " msg=" + se.getMessage());
                      assertThat(se.getSQLState()).isEqualTo("23502"); // not_null_violation
                  });

                conn.rollback();
                conn.setAutoCommit(false);

                // 3. FK constraint violation (safety_area referencing non-existent parent_id = 99999999)
                assertThatThrownBy(() -> {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO safety.safety_area (area_code, area_name, parent_id) VALUES ('AREA-SPIKE-FK', 'FK Test Area', 99999999)")) {
                        ps.executeUpdate();
                    }
                }).isInstanceOf(SQLException.class)
                  .satisfies(e -> {
                      SQLException se = (SQLException) e;
                      System.out.println("[SPIKE] FK violation caught: SQLSTATE=" + se.getSQLState() + " msg=" + se.getMessage());
                      assertThat(se.getSQLState()).isEqualTo("23503"); // foreign_key_violation
                  });

                System.out.println("[SPIKE] All constraints (UNIQUE/NOT NULL/FK) verified successfully.");

            } finally {
                conn.rollback();
            }
        }
    }
}
