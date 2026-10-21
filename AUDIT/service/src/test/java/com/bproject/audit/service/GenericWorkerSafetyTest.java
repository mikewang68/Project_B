package com.bproject.audit.service;
import com.bproject.audit.client.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GenericWorkerSafetyTest {
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();JdbcTemplate db;LogStore store;HttpServer server;
    TrustSources.Route route;Path config;String key;LogEvent event;int polls;AtomicInteger posts=new AtomicInteger(),retries=new AtomicInteger();
    String mode="normal";String snapshot;boolean retried;
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds);store=new LogStore(db,mapper,new TransactionTemplate(new DataSourceTransactionManager(ds)),true,true);store.initialize();
        event=new LogEvent(1,"SAFE-1",Instant.parse("2026-01-01T00:00:00.123456789Z"),"ehealth","test","z","u","AUTHENTICATED","assets","READ","A1","SUCCESS","GET","/devices","trace",1,"REQUEST","synthetic");key=event.storageKey();
        store.append(event,new ServiceIdentity("p","unused","ehealth","test","z",Set.of("WRITE")));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/integrations/evidence/events",exchange->{
            try{
                String path=exchange.getRequestURI().getPath();Map<String,Object> response;
                if(path.endsWith("/capabilities"))response=Map.of("instanceId","local-safety","isolationEnabled",true,"ingressSchemaVersions",List.of(1));
                else if(path.endsWith("/retry")){retries.incrementAndGet();retried=true;response=receipt("PROCESSING");response.put("queued",true);}
                else if(exchange.getRequestMethod().equals("POST")) {
                    posts.incrementAndGet();snapshot=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    if(mode.equals("conflict")){exchange.sendResponseHeaders(409,-1);exchange.close();return;}
                    response=receipt(mode.equals("false-commit")?"COMMITTED":"ACCEPTED");
                } else {
                    if(snapshot==null){byte[] missing=mapper.writeValueAsBytes(Map.of("code","RECEIPT_NOT_FOUND","message","missing"));exchange.sendResponseHeaders(404,missing.length);exchange.getResponseBody().write(missing);exchange.close();return;}
                    polls++;response=receipt(mode.equals("action")&&!retried?"ACTION_REQUIRED":"COMMITTED");
                }
                byte[] bytes=mapper.writeValueAsBytes(response);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            }catch(Exception e){exchange.sendResponseHeaders(500,-1);exchange.close();}
        });server.start();
        Path local=Files.createTempDirectory("audit-safety-");local.toFile().deleteOnExit();Path token=local.resolve("source.token");Files.writeString(token,"TEST-ONLY-"+"s".repeat(32));token.toFile().deleteOnExit();
        route=new TrustSources.Route("safety-source","test","z","ehealth","org","system", "http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1/integrations/evidence/events",token.toString(),true,true,true,"2026-01-01T00:00:00Z","local-safety");
        config=local.resolve("routes.json");mapper.writeValue(config.toFile(),List.of(route));config.toFile().deleteOnExit();
    }
    @AfterEach void stop(){server.stop(0);}
    Map<String,Object> receipt(String state) throws Exception {
        String id="11111111-1111-4111-8111-111111111111";Map<String,Object> reply=new LinkedHashMap<>();
        reply.put("id",id);reply.put("sourceSystem",route.sourceSystem());reply.put("sourceEventId",key);reply.put("rootId",id);reply.put("version",1);reply.put("supersedesId",null);
        reply.put("status",state);reply.put("taskState",state.equals("COMMITTED")?"DONE":state.equals("ACTION_REQUIRED")?"FAILED":"READY");
        reply.put("fileState",state.equals("COMMITTED")?"STORED":"PENDING");reply.put("chainState",state.equals("COMMITTED")?"COMMITTED":"PENDING");
        reply.put("originalSha256",mode.equals("bad-hash")?"0".repeat(64):CanonicalJson.hash(snapshot));
        reply.put("eventSha256",GenericEvidenceMapping.eventHash(mapper,snapshot,mapper.valueToTree(reply),route));
        reply.put("originalCid",state.equals("COMMITTED")?"synthetic-original":null);reply.put("manifestCid",state.equals("COMMITTED")?"synthetic-manifest":null);
        reply.put("manifestSha256",state.equals("COMMITTED")?"a".repeat(64):null);reply.put("txId",state.equals("COMMITTED")?"synthetic-transaction":null);
        reply.put("blockNumber",null);reply.put("outcomeUnknown",state.equals("ACTION_REQUIRED")||mode.equals("false-commit"));reply.put("errorCode",null);reply.put("nextRetryAt",null);return reply;
    }
    GenericEvidenceWorker worker()throws Exception{return new GenericEvidenceWorker(store,mapper,true,config.toString(),300);}
    void tick()throws Exception{db.update("UPDATE log_chain SET next_attempt=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),key);worker().runBatch();}
    @Test void acceptedThenCommittedAndRawMetadataKept()throws Exception{tick();assertEquals("ACCEPTED",store.receiptByKey(key).get("chainState"));tick();assertEquals("COMMITTED",store.receiptByKey(key).get("chainState"));assertNotNull(store.receiptByKey(key).get("trust"));assertEquals(1,posts.get());}
    @Test void hashMismatchStopsWithoutAutomaticRetry()throws Exception{mode="bad-hash";tick();assertEquals("FAILED",store.receiptByKey(key).get("chainState"));tick();assertEquals(1,posts.get());assertEquals(0,retries.get());}
    @Test void unknownOutcomeCannotBeMarkedCommitted()throws Exception{mode="false-commit";tick();assertEquals("FAILED",store.receiptByKey(key).get("chainState"));assertEquals("INCONSISTENT_COMMIT_RECEIPT",store.receiptByKey(key).get("lastError"));}
    @Test void persistedSnapshotTamperingStopsBeforeAnotherCall()throws Exception{tick();db.update("UPDATE log_trust_delivery SET request_text=request_text||' ' WHERE id=?",key);tick();assertEquals("LOCAL_SNAPSHOT_DRIFT",store.receiptByKey(key).get("lastError"));assertEquals(1,posts.get());}
    @Test void conflictCannotBeResetToBlindSubmission()throws Exception{mode="conflict";tick();assertEquals("FAILED",store.receiptByKey(key).get("chainState"));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->worker().retry(new ServiceIdentity("r","unused","ehealth","test","z",Set.of("RETRY")),event.eventId(),"ehealth"));assertEquals(1,posts.get());}
    @Test void sourceApprovalAndScopeNeverFallback()throws Exception{
        var disabled=new TrustSources.Route(route.id(),route.domainId(),route.zoneId(),route.appCode(),route.orgId(),route.sourceSystem(),route.baseUrl(),route.tokenFile(),true,false,true,route.eligibleFrom(),route.instanceId());
        mapper.writeValue(config.toFile(),List.of(disabled));tick();assertEquals(0,posts.get());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM log_trust_delivery",Integer.class));
        var otherZone=new TrustSources.Route(route.id(),route.domainId(),"other-zone",route.appCode(),route.orgId(),route.sourceSystem(),route.baseUrl(),route.tokenFile(),true,true,true,route.eligibleFrom(),route.instanceId());
        mapper.writeValue(config.toFile(),List.of(otherZone));tick();assertEquals(0,posts.get());
    }
    @Test void targetInstanceMismatchStopsBeforeSubmit()throws Exception{
        var wrong=new TrustSources.Route(route.id(),route.domainId(),route.zoneId(),route.appCode(),route.orgId(),route.sourceSystem(),route.baseUrl(),route.tokenFile(),true,true,true,route.eligibleFrom(),"other-instance");
        mapper.writeValue(config.toFile(),List.of(wrong));tick();assertEquals(0,posts.get());assertEquals("TARGET_INSTANCE_OR_ISOLATION_DRIFT",store.receiptByKey(key).get("lastError"));
    }
    @Test void trustRetryIsNotGrantedByLocalRetryAlone()throws Exception{
        var restricted=new TrustSources.Route(route.id(),route.domainId(),route.zoneId(),route.appCode(),route.orgId(),route.sourceSystem(),route.baseUrl(),route.tokenFile(),true,true,false,route.eligibleFrom(),route.instanceId());
        mapper.writeValue(config.toFile(),List.of(restricted));mode="action";tick();tick();
        var exception=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->worker().retry(new ServiceIdentity("r","unused","ehealth","test","z",Set.of("RETRY")),event.eventId(),"ehealth"));
        assertEquals(403,exception.getStatusCode().value());assertEquals(0,retries.get());assertEquals("FAILED",store.receiptByKey(key).get("chainState"));
    }
    @Test void explicitRetryCoordinatesBothSidesAndKeepsSnapshot()throws Exception{
        mode="action";tick();String original=snapshot;tick();assertEquals("FAILED",store.receiptByKey(key).get("chainState"));assertEquals(0,retries.get());
        worker().retry(new ServiceIdentity("r","unused","ehealth","test","z",Set.of("RETRY")),event.eventId(),"ehealth");assertEquals(1,retries.get());assertEquals("ACCEPTED",store.receiptByKey(key).get("chainState"));tick();assertEquals("COMMITTED",store.receiptByKey(key).get("chainState"));assertEquals(original,snapshot);
    }
    @Test void readOnlyCannotInvokePrivilegedRetry()throws Exception{assertThrows(org.springframework.web.server.ResponseStatusException.class,()->worker().retry(new ServiceIdentity("r","unused","ehealth","test","z",Set.of("READ")),event.eventId(),"ehealth"));assertEquals(0,retries.get());}
}
