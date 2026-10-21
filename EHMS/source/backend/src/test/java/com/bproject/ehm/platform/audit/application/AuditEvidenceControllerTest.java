package com.bproject.ehm.platform.audit.application;

import com.bproject.audit.client.CanonicalJson;
import com.bproject.ehm.platform.audit.adapter.in.web.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditEvidenceControllerTest {
    EhmAuditedTasks tasks;AuditEvidenceController controller;ObjectMapper mapper;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");var db=new JdbcTemplate(ds);mapper=new ObjectMapper().findAndRegisterModules();
        var relay=new EhmAuditRelay(db,mapper,"ehm",true,"test","zone","","","audit_outbox");relay.initialize();
        tasks=new EhmAuditedTasks(db,mapper,relay,new TransactionTemplate(new DataSourceTransactionManager(ds)),"ehm",true,900,"audit_task");tasks.initialize();
        controller=new AuditEvidenceController(tasks,relay,db,mapper,true,"u1","test-version");
    }
    @Test void exportRequiresTrustedAllowlistedActor(){
        var forged=new MockHttpServletRequest();forged.addHeader("X-User-Id","u1");assertThrows(ResponseStatusException.class,()->controller.export(forged));
        forged.setUserPrincipal(()->"u2");assertThrows(ResponseStatusException.class,()->controller.export(forged));
    }
    @Test void backendBytesAreBoundedAndHashMatchesActualDatabase() throws Exception {
        var r=new MockHttpServletRequest("GET","/api/ehm/v1/system/audit-evidence/export");r.setUserPrincipal(()->"u1");
        r.addHeader("Authorization","Bearer do-not-export");r.setContent("private-input".getBytes());
        String id=tasks.start(r,"EXPORT","audit-package");var response=controller.export(r);String text=new String(response.getBody(),StandardCharsets.UTF_8);
        assertEquals(CanonicalJson.hash(text),response.getHeaders().getFirst("X-EHM-Evidence-Sha256"));assertTrue(text.contains("H2"));assertTrue(text.contains(id));
        assertFalse(text.contains("do-not-export"));assertFalse(text.contains("private-input"));assertEquals("no-store",response.getHeaders().getCacheControl());
        assertTrue(tasks.finish(id,"SUCCESS"));assertEquals("SUCCEEDED",tasks.latest().get(0).get("state"));
    }
    @Test void anonymousAndOtherUsersCannotReadAllTaskIdentities(){
        var first=new MockHttpServletRequest();first.setUserPrincipal(()->"u1");tasks.start(first,"EXECUTE","health");
        var anonymous=new MockHttpServletRequest();assertEquals(List.of(),controller.list(anonymous).get("tasks"));
        var other=new MockHttpServletRequest();other.setUserPrincipal(()->"u2");assertEquals(List.of(),controller.list(other).get("tasks"));
        assertEquals(1,((List<?>)controller.list(first).get("tasks")).size());
    }
}
