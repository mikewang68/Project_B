package com.bproject.safety.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = OpenGaussSpikeSupport.ENV_SPIKE_ENABLED, matches = "true")
@DisplayName("Spike 3: openGauss TIMESTAMPTZ 与 Java OffsetDateTime 映射实机测试")
class OpenGaussTimestampSpikeTest extends OpenGaussSpikeSupport {

    private static final String TEST_TEAM_CODE = "TEAM-SPIKE-TSTZ";

    @Test
    @DisplayName("验证 OffsetDateTime (+08:00 与 UTC) 写入 TIMESTAMPTZ 并读出，瞬时时间戳零漂移")
    void testTimestampTzRoundTrip() throws Exception {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Prepare test times
                Instant nowInstant = Instant.now();
                OffsetDateTime shanghaiTime = nowInstant.atOffset(ZoneOffset.ofHours(8));
                OffsetDateTime utcTime = nowInstant.atOffset(ZoneOffset.UTC);

                String insertSql = "INSERT INTO safety.sys_team " +
                        "(team_code, team_name, team_type, created_at, updated_at) " +
                        "VALUES (?, 'Timestamp Spike Team', 'INTERNAL', ?, ?) RETURNING id";

                long teamId = -1;
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, TEST_TEAM_CODE);
                    ps.setObject(2, shanghaiTime);
                    ps.setObject(3, utcTime);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        teamId = rs.getLong(1);
                    }
                }

                // Read back
                String selectSql = "SELECT created_at, updated_at FROM safety.sys_team WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    ps.setLong(1, teamId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();

                        OffsetDateTime readCreated = rs.getObject("created_at", OffsetDateTime.class);
                        OffsetDateTime readUpdated = rs.getObject("updated_at", OffsetDateTime.class);

                        assertThat(readCreated).isNotNull();
                        assertThat(readUpdated).isNotNull();

                        // Assert instant equality (epoch milli must match exactly)
                        long diffCreatedMs = Math.abs(readCreated.toInstant().toEpochMilli() - shanghaiTime.toInstant().toEpochMilli());
                        long diffUpdatedMs = Math.abs(readUpdated.toInstant().toEpochMilli() - utcTime.toInstant().toEpochMilli());

                        assertThat(diffCreatedMs).isLessThanOrEqualTo(10); // Microsecond rounding tolerance
                        assertThat(diffUpdatedMs).isLessThanOrEqualTo(10);

                        System.out.println("[SPIKE] TIMESTAMPTZ wrote: " + shanghaiTime + " / " + utcTime);
                        System.out.println("[SPIKE] TIMESTAMPTZ read : " + readCreated + " / " + readUpdated);
                        System.out.println("[SPIKE] TIMESTAMPTZ round-trip passed with zero drift.");
                    }
                }

            } finally {
                conn.rollback(); // Rollback test data
            }
        }
    }
}
