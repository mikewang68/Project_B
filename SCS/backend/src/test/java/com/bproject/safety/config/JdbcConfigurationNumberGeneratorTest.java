package com.bproject.safety.config;

import com.bproject.safety.support.number.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcConfigurationNumberGeneratorTest {
    @Test void skipsLegacyKeysWithoutUsingScopedRepositories(){
        var counter=mock(JdbcBusinessNumberStore.class);var jdbc=mock(JdbcTemplate.class);
        when(counter.nextSequence("RULE_PER","GLOBAL")).thenReturn(1L,2L);
        when(jdbc.queryForObject("SELECT count(*) FROM safety.safety_rule WHERE rule_code=?",Long.class,"RULE-PER-001")).thenReturn(1L);
        when(jdbc.queryForObject("SELECT count(*) FROM safety.safety_rule WHERE rule_code=?",Long.class,"RULE-PER-002")).thenReturn(0L);
        assertEquals("RULE-PER-002",new JdbcConfigurationNumberGenerator(counter,jdbc).nextRuleNumber(null));
        verify(counter,times(2)).nextSequence("RULE_PER","GLOBAL");
    }
    @Test void preservesExistingFenceAndCategoryFormats(){
        var counter=mock(JdbcBusinessNumberStore.class);var jdbc=mock(JdbcTemplate.class);
        when(counter.nextSequence("FENCE","GLOBAL")).thenReturn(1001L);
        when(jdbc.queryForObject("SELECT count(*) FROM safety.safety_fence WHERE fence_code=?",Long.class,"FENCE-1001")).thenReturn(0L);
        assertEquals("FENCE-1001",new JdbcConfigurationNumberGenerator(counter,jdbc).nextFenceNumber());
    }
}
