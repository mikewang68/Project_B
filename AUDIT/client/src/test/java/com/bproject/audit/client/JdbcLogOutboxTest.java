package com.bproject.audit.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class JdbcLogOutboxTest {
    JdbcTemplate db; JdbcLogOutbox queue; ObjectMapper mapper;
    @BeforeEach void setup() {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds); db.execute("CREATE SCHEMA producer");
        mapper=new ObjectMapper().registerModule(new JavaTimeModule()); queue=new JdbcLogOutbox(db,mapper,"producer.audit_outbox"); queue.initialize();
        db.execute("CREATE TABLE business(id VARCHAR(64) PRIMARY KEY)");
    }
    LogEvent event() { return new LogEvent(1,"TEST-1",Instant.parse("2026-01-01T00:00:00Z"),"ehealth","test","zone1","u1","AUTHENTICATED","asset-tree","CREATE","D1","SUCCESS","POST","/devices","T1",1,"BUSINESS","测试操作"); }
    @Test void businessAndLogRollBackTogether() {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{db.update("INSERT INTO business VALUES('1')");queue.enqueue(event());throw new IllegalStateException("rollback");}));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM business",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM producer.audit_outbox",Integer.class));
    }
    @Test void duplicateIdenticalEventDoesNotDuplicateQueue() { queue.enqueue(event());queue.enqueue(event());assertEquals(1,queue.due().size()); }
    @Test void invalidOrSensitiveMetadataRejected() {
        assertThrows(IllegalArgumentException.class,()->new LogEvent(1,"X",Instant.now(),"ehealth","test","z","u","AUTHENTICATED","m","READ","","SUCCESS","GET","/x?password=secret","",0,"REQUEST",""));
        assertThrows(IllegalArgumentException.class,()->new LogEvent(1,"X",Instant.now(),"ehealth","test","z","u","AUTHENTICATED","m","READ","","SUCCESS","GET","/x","",0,"REQUEST","api_key=secret"));
    }
    @Test void outageKeepsDurableEvent() {
        queue.enqueue(event());new LogDispatcher(queue,mapper,"http://127.0.0.1:1/api/logs/v1/events","test-only-credential-"+"x".repeat(32)).dispatch();
        assertEquals("PENDING",db.queryForObject("SELECT state FROM producer.audit_outbox",String.class));
        assertEquals(1,db.queryForObject("SELECT attempts FROM producer.audit_outbox",Integer.class));
    }
    @Test void wrongReceiptIsNotAcknowledged() throws Exception { deliver("{\"eventId\":\"WRONG\",\"stored\":true}","PENDING"); }
    @Test void durableReceiptAcknowledged() throws Exception { deliver("{\"eventId\":\"TEST-1\",\"stored\":true,\"payloadHash\":\"HASH\",\"chainState\":\"NOT_CONFIGURED\"}","ACCEPTED"); }
    private void deliver(String reply,String expected) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String response=reply.replace("HASH",CanonicalJson.hash(CanonicalJson.encode(mapper,event())));
        server.createContext("/events",e->{byte[] body=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(202,body.length);e.getResponseBody().write(body);e.close();});server.start();
        try{queue.enqueue(event());new LogDispatcher(queue,mapper,"http://127.0.0.1:"+server.getAddress().getPort()+"/events","test-only-credential-"+"x".repeat(32)).dispatch();assertEquals(expected,db.queryForObject("SELECT state FROM producer.audit_outbox",String.class));}finally{server.stop(0);}
    }
}
