package com.bproject.ehm.platform.audit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EhmAuditRelayTest {
    @Test void queuePersistsAndDiagnosticsDoNotReturnBodyOrCredentials() throws Exception {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        var mapper=new ObjectMapper().findAndRegisterModules();
        var relay=new EhmAuditRelay(db,mapper,"ehm",true,"demo","demo","","","audit_outbox");relay.initialize();
        var request=new MockHttpServletRequest("POST","/api/ehm/v1/shift-handovers/H1/submit");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,Map.of("handoverNo","H1"));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/ehm/v1/shift-handovers/{handoverNo}/submit");
        request.addHeader("Authorization","Bearer private-credential");request.setContent("private AI prompt".getBytes());
        relay.record(request,"SUCCESS","BUSINESS",12);
        String status=mapper.writeValueAsString(relay.status());
        assertTrue(status.contains("H1"));assertTrue(status.contains("EXECUTE"));assertTrue(status.contains("PENDING"));
        assertFalse(status.contains("private-credential"));assertFalse(status.contains("private AI prompt"));
        assertFalse(status.contains("payloadHash"));assertEquals(0L,relay.status().get("enqueueFailuresSinceStartup"));
    }
    @Test void enqueueFailureIsObservableWithoutFalseSuccess() throws Exception {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        var relay=new EhmAuditRelay(db,new ObjectMapper().findAndRegisterModules(),"ehm",true,"demo","demo","","","audit_outbox");relay.initialize();
        db.execute("DROP TABLE ehm.audit_outbox");
        assertThrows(RuntimeException.class,()->relay.record(new MockHttpServletRequest("POST","/api/ehm/v1/devices"),"SUCCESS","BUSINESS",0));
        relay.initialize();
        assertEquals(1L,relay.status().get("enqueueFailuresSinceStartup"));assertNotEquals("",relay.status().get("lastEnqueueFailure"));
    }
}
