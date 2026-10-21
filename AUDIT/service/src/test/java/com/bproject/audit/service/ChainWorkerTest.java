package com.bproject.audit.service;
import com.bproject.audit.client.LogEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Contract stub only: these tests do NOT contact Fabric or certify chain deployment. */
class ChainWorkerTest {
    @Test void acceptedIsNotCommitted() throws Exception { verify("ACCEPTED",false,"ACCEPTED"); }
    @Test void committedRequiresMatchingReceipt() throws Exception { verify("COMMITTED",false,"COMMITTED"); }
    @Test void wrongHashNeverMarksCommitted() throws Exception { verify("COMMITTED",true,"PENDING"); }
    @Test void failedLedgerIsRetryableOnlyByExplicitAction() throws Exception { verify("FAILED",false,"FAILED"); }
    private void verify(String remoteState,boolean wrongHash,String expected) throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var db=new JdbcTemplate(ds);var store=new LogStore(db,mapper,new TransactionTemplate(new DataSourceTransactionManager(ds)),true,true);store.initialize();
        var event=new LogEvent(1,"CHAIN-TEST",Instant.now(),"ehealth","test","z","u","AUTHENTICATED","m","READ","","SUCCESS","GET","/devices","",0,"REQUEST","测试");
        store.append(event,new ServiceIdentity("p","test","ehealth","test","z",java.util.Set.of("WRITE")));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/logs",request->{
            if(request.getRequestMethod().equals("GET")){request.sendResponseHeaders(404,-1);request.close();return;}
            var body=mapper.readTree(request.getRequestBody());
            byte[] reply=mapper.writeValueAsBytes(Map.of("eventKey",body.path("eventKey").asText(),"payloadHash",wrongHash?"bad-hash":body.path("payloadHash").asText(),"state",remoteState,"transactionId",remoteState.equals("COMMITTED")?"f".repeat(64):""));
            request.sendResponseHeaders(202,reply.length);request.getResponseBody().write(reply);request.close();
        });server.start();var token=Files.createTempFile("chain-test-key-",".txt");Files.writeString(token,"TEST-ONLY-"+"x".repeat(32));
        try{var worker=new ChainWorker(store,mapper,true,"http://127.0.0.1:"+server.getAddress().getPort()+"/logs",token.toString());worker.runBatch();assertEquals(expected,store.receiptByKey(event.storageKey()).get("chainState"));
            if(expected.equals("COMMITTED"))assertEquals("f".repeat(64),store.receiptByKey(event.storageKey()).get("transactionId"));
            else assertNotEquals("COMMITTED",store.receiptByKey(event.storageKey()).get("chainState"));
        }finally{server.stop(0);Files.deleteIfExists(token);}
    }
}
