package com.bproject.ehm.platform.audit.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring filters, MVC, aspects and durable queue, on isolated test-only H2. No model/server access. */
@SpringBootTest(properties={
    "ehm.auth.mode=disabled",
    "spring.datasource.url=jdbc:h2:mem:ehm-audit-server-test;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "ehm.persistence.auto-ddl=true", "ehm.audit.endpoint=", "ehm.audit.domain=demo", "ehm.audit.zone=demo",
    "ehm.audit.iam-me-url=", "ehm.audit.iam-required=false", "ehm.ai.enabled=false",
    "ehm.config.path=target/nonexistent-audit-test-config.json", "ehm.cors.allowed-origins=http://localhost:8088"
})
@ActiveProfiles("server") @AutoConfigureMockMvc
class AuditServerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Test void crudRejectionAiFallbackAndClientIntentPersistWithHonestOutcomes() throws Exception {
        mvc.perform(post("/api/ehm/v1/devices").contentType(MediaType.APPLICATION_JSON)
                .header("X-EHM-Operator","forged-admin").content("{\"code\":\"AUDIT-IT-01\",\"name\":\"Virtual audit test\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/ehm/v1/devices/AUDIT-IT-01")).andExpect(status().isOk());
        mvc.perform(put("/api/ehm/v1/devices/AUDIT-IT-01").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Virtual updated\"}")).andExpect(status().isOk());
        mvc.perform(delete("/api/ehm/v1/devices/AUDIT-IT-01")).andExpect(status().isNoContent());
        mvc.perform(post("/api/ehm/v1/devices").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/ehm/v1/assistant/test")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/api/ehm/v1/devices").header("Origin","http://untrusted.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ehm/v1/system/client-events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"menuId\":\"asset-tree\",\"action\":\"EXPORT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("STARTED"));
        boolean aiFailed=false,denied=false,intent=false,update=false,create=false;
        for (var row:db.queryForList("SELECT payload,state FROM ehm.audit_outbox")) {
            var e=mapper.readTree((String)row.get("payload"));
            assertEquals("anonymous",e.path("userId").asText());assertEquals("PENDING",row.get("state"));
            if(e.path("menuId").asText().equals("ai-assistant"))aiFailed|=e.path("outcome").asText().equals("FAILED");
            denied|=e.path("outcome").asText().equals("DENIED");
            intent|=e.path("action").asText().equals("EXPORT")&&e.path("outcome").asText().equals("STARTED");
            update|=e.path("action").asText().equals("UPDATE")&&e.path("objectId").asText().equals("AUDIT-IT-01");
            create|=e.path("action").asText().equals("CREATE")&&e.path("objectId").asText().equals("AUDIT-IT-01");
        }
        assertTrue(aiFailed);assertTrue(denied);assertTrue(intent);assertTrue(update);assertTrue(create);
        mvc.perform(get("/api/ehm/v1/system/audit-delivery"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.durableQueue").value(true))
                .andExpect(jsonPath("$.deliveryConfigured").value(false)).andExpect(jsonPath("$.recentEvents").isArray());
    }
}
