package com.bproject.ehm.platform.audit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EhmAuditedTasksTest {
    JdbcTemplate db;ObjectMapper mapper;EhmAuditRelay relay;EhmAuditedTasks tasks;TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");db=new JdbcTemplate(ds);
        mapper=new ObjectMapper().findAndRegisterModules();relay=new EhmAuditRelay(db,mapper,"ehm",true,"test","zone","","","audit_outbox");relay.initialize();
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tasks=new EhmAuditedTasks(db,mapper,relay,tx,"ehm",true,900,"audit_task");tasks.initialize();
    }
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("POST","/api/ehm/v1/devices/D1/health-assessments/run");r.setUserPrincipal(()->"trusted-user");r.addHeader("Authorization","Bearer never-export-this");r.setContent("private prompt".getBytes());return r;}
    @Test void realLifecycleIsDurableAndPreservesTrustedActor() throws Exception {
        String id=tasks.start(request(),"EXECUTE","health");assertEquals("RUNNING",tasks.latest().get(0).get("state"));
        assertTrue(tasks.finish(id,"SUCCESS"));assertFalse(tasks.finish(id,"SUCCESS"));
        assertEquals("SUCCEEDED",tasks.latest().get(0).get("state"));
        var payloads=db.queryForList("SELECT payload FROM ehm.audit_outbox",String.class);assertEquals(2,payloads.size());
        String all=payloads.toString();assertTrue(all.contains("trusted-user"));assertTrue(all.contains("STARTED"));assertTrue(all.contains("SUCCESS"));assertFalse(all.contains("never-export-this"));assertFalse(all.contains("private prompt"));
    }
    @Test void failedExecutionAndDenialHaveRealTerminalEvents(){
        for(String outcome:List.of("FAILED","DENIED")){String id=tasks.start(request(),"EXECUTE","health");assertTrue(tasks.finish(id,outcome));}
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM ehm.audit_outbox",Integer.class));
        assertTrue(db.queryForList("SELECT state FROM ehm.audit_task",String.class).stream().allMatch("FAILED"::equals));
    }
    @Test void restartReconcilesExpiredExecutionAsUnknownWithoutRerun(){
        String id=tasks.start(request(),"EXECUTE","health");db.update("UPDATE ehm.audit_task SET lease_until=? WHERE task_id=?",Timestamp.from(Instant.now().minusSeconds(1)),id);
        var restarted=new EhmAuditedTasks(db,mapper,relay,tx,"ehm",false,900,"audit_task");restarted.initialize();restarted.reconcileExpired();restarted.reconcileExpired();
        assertEquals("UNKNOWN",restarted.latest().get(0).get("state"));assertFalse(restarted.finish(id,"SUCCESS"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ehm.audit_outbox",Integer.class));
        String last=db.queryForObject("SELECT payload FROM ehm.audit_outbox WHERE event_id=?",String.class,id+"-UNKNOWN");
        assertTrue(last.contains("STARTED"));assertFalse(last.contains("\"outcome\":\"SUCCESS\""));
    }
    @Test void queueFailureRollsBackTaskStart(){
        db.execute("DROP TABLE ehm.audit_outbox");assertThrows(RuntimeException.class,()->tasks.start(request(),"EXECUTE","health"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ehm.audit_task",Integer.class));
    }
    @Test void terminalQueueFailureDoesNotClaimSuccess(){
        String id=tasks.start(request(),"EXECUTE","health");db.execute("DROP TABLE ehm.audit_outbox");
        assertThrows(RuntimeException.class,()->tasks.finish(id,"SUCCESS"));assertEquals("RUNNING",tasks.latest().get(0).get("state"));
    }
    @Test void deadlineCannotBeKeptAliveForever(){
        String id=tasks.start(request(),"EXECUTE","health");db.update("UPDATE ehm.audit_task SET deadline_at=? WHERE task_id=?",Timestamp.from(Instant.now().minusSeconds(1)),id);
        tasks.reconcileExpired();assertEquals("UNKNOWN",tasks.latest().get(0).get("state"));assertFalse(tasks.finish(id,"SUCCESS"));
    }
}
