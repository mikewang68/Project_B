package com.bproject.ehm.platform.persistence.opengauss;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenGaussJsonStoreTest {
    @Mock
    private JdbcTemplate jdbc;

    @Test
    void initializesOnlyTheEhmSchemaAndAggregateTable() {
        OpenGaussJsonStore store = new OpenGaussJsonStore(jdbc, mapper(), "ehm", "aggregate_store", true);

        store.initialize();

        verify(jdbc).execute("CREATE SCHEMA IF NOT EXISTS ehm");
        verify(jdbc).execute(startsWith("CREATE TABLE IF NOT EXISTS ehm.aggregate_store"));
        verify(jdbc).execute(startsWith("CREATE INDEX IF NOT EXISTS idx_ehm_aggregate_updated"));
    }

    @Test
    void savesJsonThroughUpdateFirstUpsert() {
        when(jdbc.update(startsWith("UPDATE ehm.aggregate_store"),
                any(), any(), eq("asset"), eq("GT-01"))).thenReturn(1);
        OpenGaussJsonStore store = new OpenGaussJsonStore(jdbc, mapper(), "ehm", "aggregate_store", false);
        Map<String, Object> value = Map.of("code", "GT-01", "health", 88);

        assertEquals(value, store.save("asset", "GT-01", value));

        verify(jdbc).update(startsWith("UPDATE ehm.aggregate_store"),
                any(), any(), eq("asset"), eq("GT-01"));
    }

    @Test
    void rejectsUnsafeSqlIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpenGaussJsonStore(jdbc, mapper(), "ehm;drop schema", "aggregate_store", false));
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
