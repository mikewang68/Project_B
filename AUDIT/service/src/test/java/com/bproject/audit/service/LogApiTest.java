package com.bproject.audit.service;

import com.bproject.audit.client.LogEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:auditApi;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","audit.auto-ddl=true","audit.chain.enabled=false"})
@AutoConfigureMockMvc
class LogApiTest {
    static final String WRITER="TEST-ONLY-writer-"+"w".repeat(32),READER="TEST-ONLY-reader-"+"r".repeat(32),OTHER="TEST-ONLY-other-"+"o".repeat(32);
    @DynamicPropertySource static void clients(DynamicPropertyRegistry registry) throws Exception {
        var file=Files.createTempFile("audit-test-clients-",".json");file.toFile().deleteOnExit();
        Files.writeString(file,new ObjectMapper().writeValueAsString(List.of(new ServiceIdentity("ehm",WRITER,"ehealth","test","zone1",Set.of("WRITE","READ")),new ServiceIdentity("auditor",READER,"*","test","zone1",Set.of("READ","EXPORT")),new ServiceIdentity("other",OTHER,"ehealth","other","zone1",Set.of("WRITE","READ")))));
        registry.add("audit.clients-file",file::toString);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate db; @Autowired LogStore store;
    LogEvent event(String id) {return new LogEvent(1,id,Instant.parse("2026-01-01T00:00:00Z"),"ehealth","test","zone1","u1","AUTHENTICATED","asset-tree","CREATE","D1","SUCCESS","POST","/devices","T1",1,"BUSINESS","测试记录");}
    void append(String id) throws Exception { mvc.perform(post("/api/logs/v1/events").header("Authorization","Bearer "+WRITER).contentType("application/json").content(mapper.writeValueAsString(event(id)))).andExpect(status().isAccepted()).andExpect(jsonPath("$.stored").value(true)).andExpect(jsonPath("$.chainState").value("NOT_CONFIGURED")); }
    @Test void noAuthenticationRejected() throws Exception {mvc.perform(get("/api/logs/v1/events")).andExpect(status().isUnauthorized());}
    @Test void writesHaveDurablePendingChainReceipt() throws Exception {append("API-durable");assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM log_chain c JOIN log_event e ON e.id=c.id WHERE event_id='API-durable'",Integer.class));}
    @Test void idempotentReplayAndConflict() throws Exception {
        append("API-repeat");append("API-repeat");assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM log_event WHERE event_id='API-repeat'",Integer.class));
        String altered=mapper.writeValueAsString(event("API-repeat")).replace("测试记录","其他内容");
        mvc.perform(post("/api/logs/v1/events").header("Authorization","Bearer "+WRITER).contentType("application/json").content(altered)).andExpect(status().isConflict());
    }
    @Test void cannotForgeAppOrScope() throws Exception {mvc.perform(post("/api/logs/v1/events").header("Authorization","Bearer "+OTHER).contentType("application/json").content(mapper.writeValueAsString(event("API-forged")))).andExpect(status().isForbidden());}
    @Test void readOnlyCannotWriteOrRetry() throws Exception {
        mvc.perform(post("/api/logs/v1/events").header("Authorization","Bearer "+READER).contentType("application/json").content(mapper.writeValueAsString(event("API-readonly")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/logs/v1/events/API-readonly/retry?appCode=ehealth").header("Authorization","Bearer "+READER)).andExpect(status().isForbidden());
    }
    @Test void crossDomainCannotReadReceipt() throws Exception {append("API-private");mvc.perform(get("/api/logs/v1/events/API-private/receipt").header("Authorization","Bearer "+OTHER)).andExpect(status().isNotFound());}
    @Test void pageAndUnknownFieldsValidated() throws Exception {
        mvc.perform(get("/api/logs/v1/events?size=1000").header("Authorization","Bearer "+READER)).andExpect(status().isBadRequest());
        String extra=mapper.writeValueAsString(event("API-extra")).replaceFirst("\\{","{\"password\":\"secret\",");
        mvc.perform(post("/api/logs/v1/events").header("Authorization","Bearer "+WRITER).contentType("application/json").content(extra)).andExpect(status().isBadRequest());
    }
    @Test void queryAndExportAreAudited() throws Exception {
        append("API-query");mvc.perform(get("/api/logs/v1/events?appCode=ehealth").header("Authorization","Bearer "+READER)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/logs/v1/export?appCode=ehealth").header("Authorization","Bearer "+READER)).andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=audit-latest-100.csv"));
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM log_event WHERE app_code='audit' AND action='EXPORT'",Integer.class)>0);
    }
}
