package com.bproject.safety.module.alert.repository;

import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 8 / P0-4：态势计数 GROUP BY 下推的单元测试（大屏 / 统计口径）。
 */
@DisplayName("Step 8: JdbcAlertRepository 计数聚合下推测试")
class JdbcAlertCountAggregationTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private JdbcAlertRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        repository = new JdbcAlertRepository(jdbcTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.bproject.safety.support.masterdata.DemoMasterData(),
                java.time.Clock.systemDefaultZone());
    }

    @SuppressWarnings("unchecked")
    private void stubRows(String... pairs) throws Exception {
        org.mockito.Mockito.doAnswer(inv -> {
            RowCallbackHandler handler = (RowCallbackHandler) inv.getArgument(2);
            for (int i = 0; i < pairs.length; i += 2) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString(1)).thenReturn(pairs[i]);
                when(rs.getLong(2)).thenReturn(Long.parseLong(pairs[i + 1]));
                handler.processRow(rs);
            }
            return null;
        }).when(jdbcTemplate).query(anyString(), any(Map.class), any(RowCallbackHandler.class));
    }

    @Test
    @DisplayName("countByStatus: GROUP BY status_code 计数映射")
    void testCountByStatus() throws Exception {
        stubRows("PENDING_CONFIRM", "3", "CLOSED", "7");
        Map<String, Long> out = repository.countByStatus();
        assertThat(out).containsEntry("PENDING_CONFIRM", 3L).containsEntry("CLOSED", 7L).hasSize(2);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(Map.class), any(RowCallbackHandler.class));
        assertThat(sql.getValue()).contains("GROUP BY status_code");
    }

    @Test
    @DisplayName("countOpenByRisk: 未关闭口径 + GROUP BY risk_level_code")
    void testCountOpenByRisk() throws Exception {
        stubRows("SEVERE", "2", "URGENT", "1");
        Map<String, Long> out = repository.countOpenByRisk();
        assertThat(out).containsEntry("SEVERE", 2L).containsEntry("URGENT", 1L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(Map.class), any(RowCallbackHandler.class));
        assertThat(sql.getValue())
                .contains("status_code <> 'CLOSED'")
                .contains("GROUP BY risk_level_code");
    }

    @Test
    @DisplayName("countByEventType: COALESCE(event_type, title) 回退口径")
    void testCountByEventType() throws Exception {
        stubRows("危险区域闯入", "5");
        Map<String, Long> out = repository.countByEventType();
        assertThat(out).containsEntry("危险区域闯入", 5L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(Map.class), any(RowCallbackHandler.class));
        assertThat(sql.getValue()).contains("COALESCE(event_type, title)");
    }
}
