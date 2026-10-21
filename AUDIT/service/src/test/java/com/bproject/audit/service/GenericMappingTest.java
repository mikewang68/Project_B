package com.bproject.audit.service;
import com.bproject.audit.client.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class GenericMappingTest {
    @Test void matchesPublishedNanosecondAndInt64Vector() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var event=new LogEvent(1,"SYNTHETIC-"+"a".repeat(118),Instant.parse("2026-01-01T00:00:00.123456789Z"),"TRUST","DEMO_DOMAIN","DEMO_ZONE","synthetic-user","AUTHENTICATED","events","READ","synthetic-object","SUCCESS","GET","/synthetic/events","synthetic-trace",Long.MAX_VALUE,"BUSINESS","Synthetic immutable metadata only");
        String payload=CanonicalJson.encode(mapper,event),hash=CanonicalJson.hash(payload);
        assertEquals("5df7dcdd51178183e770e892bfe878d7cb317f6be638b581c00ada29536ca0f6",hash);
        String request=GenericEvidenceMapping.request(mapper,event.storageKey(),payload,hash);
        assertEquals("807ac614f643592347b9ec34a254c56cdcca5a959d4efcddda1751b3ae6fea1f",CanonicalJson.hash(request));
        assertEquals(944,request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(request.contains("9223372036854775807"));assertTrue(request.contains("00:00:00.123456Z"));assertTrue(request.contains("00:00:00.123456789Z"));
        var route=new TrustSources.Route("synthetic-audit","DEMO_DOMAIN","DEMO_ZONE","TRUST","SYNTHETIC_ORG","SYNTHETIC_AUDIT","", "",false,false,false,"","");
        String id="11111111-1111-4111-8111-111111111111";
        var receipt=mapper.valueToTree(Map.of("id",id,"rootId",id,"version",1));
        assertEquals("409911289d172ea46b992db4826818844776127741c09507ad7993ff60d807f1",GenericEvidenceMapping.eventHash(mapper,request,receipt,route));
    }
}
