package com.bproject.audit.client;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class LegacyOutcomeTest {
    @Test void canonicalActionsRemainCanonical(){var event=LegacyLogMapper.map(Map.of("id","1","created_at",java.time.Instant.EPOCH,"action","CREATE","result","OK"),"iam","test","z",java.time.ZoneId.of("UTC"));assertEquals("CREATE",event.action());}
    @Test void denialsNeverBecomeSuccess(){for(String r:new String[]{"DENIED"," forbidden ","unauthorized","401","403 Forbidden"})assertEquals("DENIED",LegacyLogMapper.outcome(Map.of("result",r)));}
    @Test void statusOverridesMisleadingSuccess(){assertEquals("DENIED",LegacyLogMapper.outcome(Map.of("result","success","http_status",403)));assertEquals("FAILED",LegacyLogMapper.outcome(Map.of("result","success","status_code",500)));}
    @Test void explicitResultsAndUnknown(){assertEquals("SUCCESS",LegacyLogMapper.outcome(Map.of("result","OK")));assertEquals("STARTED",LegacyLogMapper.outcome(Map.of("result","started")));assertThrows(IllegalArgumentException.class,()->LegacyLogMapper.outcome(Map.of("result","unmapped")));}
}
