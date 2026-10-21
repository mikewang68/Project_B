package com.bproject.audit.service;

import com.bproject.audit.client.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Supplied official simulator + OUR actual mapper, JDBC persistence, worker and local state assertions. */
@EnabledIfSystemProperty(named="trust.selfcheck.home",matches=".+")
class OfficialSelfcheckTest {
    @ParameterizedTest @ValueSource(strings={"normal","disconnect","unavailable","timeout","action-required"})
    void realWorkerAgainstOfficialLocalSimulator(String scenario) throws Exception {
        Path home=Path.of(System.getProperty("trust.selfcheck.home")).toAbsolutePath();
        var mapper=new ObjectMapper().findAndRegisterModules();
        Path run=home.resolve(".local/audit-"+scenario+"-"+UUID.randomUUID());Files.createDirectories(run);
        Path connection=run.resolve("connection.json"),report=run.resolve("serve.json");
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        Process server=new ProcessBuilder(java,"-jar",home.resolve("trust-selfcheck.jar").toString(),"serve","--config",home.resolve("examples/oplog-source.json").toString(),"--scenario",scenario,"--seconds","12","--connection",connection.toString(),"--report",report.toString())
                .redirectErrorStream(true).redirectOutput(run.resolve("tool-output.txt").toFile()).start();
        Throwable callerFailure=null;
        try {
            for(int i=0;i<100&&!Files.exists(connection)&&server.isAlive();i++)Thread.sleep(50);
            assertTrue(Files.exists(connection),"Simulator did not create private connection file");
            var conn=mapper.readTree(Files.readString(connection));
            assertEquals("local-simulation",conn.path("mode").asText());
            String base=conn.path("baseUrl").asText();assertTrue(base.startsWith("http://127.0.0.1:"));
            Path token=run.resolve("synthetic.token");Files.writeString(token,conn.path("token").asText());
            var pre=new TrustSources.Route("selfcheck","DEMO_DOMAIN","DEMO_ZONE","TRUST","synthetic-org","synthetic-system",base,token.toString(),true,true,false,"2026-01-01T00:00:00Z","probe");
            String instance=new GenericEvidenceHttp(mapper).call(pre,"/capabilities",null).body().path("instanceId").asText();
            var route=new TrustSources.Route(pre.id(),pre.domainId(),pre.zoneId(),pre.appCode(),pre.orgId(),pre.sourceSystem(),base,token.toString(),true,true,false,pre.eligibleFrom(),instance);
            Path routes=run.resolve("routes.json");mapper.writeValue(routes.toFile(),List.of(route));
            var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            var db=new JdbcTemplate(ds);var store=new LogStore(db,mapper,new TransactionTemplate(new DataSourceTransactionManager(ds)),true,true);store.initialize();
            var event=new LogEvent(1,"SYNTHETIC-"+"a".repeat(118),Instant.parse("2026-01-01T00:00:00.123456789Z"),"TRUST","DEMO_DOMAIN","DEMO_ZONE","synthetic-user","AUTHENTICATED","events","READ","synthetic-object","SUCCESS","GET","/synthetic/events","synthetic-trace",Long.MAX_VALUE,"BUSINESS","Synthetic immutable metadata only");
            store.append(event,new ServiceIdentity("producer","test","TRUST","DEMO_DOMAIN","DEMO_ZONE",Set.of("WRITE")));
            String key=event.storageKey();
            var worker=new GenericEvidenceWorker(store,mapper,true,routes.toString(),300);
            db.update("UPDATE log_chain SET next_attempt=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),key);
            worker.runBatch();assertNotEquals("COMMITTED",store.receiptByKey(key).get("chainState"));
            String first=db.queryForObject("SELECT request_text FROM log_trust_delivery WHERE id=?",String.class,key);
            Path request=run.resolve("actual-request.json");Files.writeString(request,first);
            Process check=new ProcessBuilder(java,"-jar",home.resolve("trust-selfcheck.jar").toString(),"check","--config",home.resolve("examples/oplog-source.json").toString(),"--report",run.resolve("check.json").toString(),request.toString())
                    .redirectErrorStream(true).redirectOutput(run.resolve("check-output.txt").toFile()).start();
            assertTrue(check.waitFor(10,TimeUnit.SECONDS));assertEquals(0,check.exitValue(),"Official request check failed");
            // Simulate application restart: only JDBC snapshot survives, not an in-memory request object.
            worker=new GenericEvidenceWorker(store,mapper,true,routes.toString(),300);
            for(int i=0;i<3;i++) {
                db.update("UPDATE log_chain SET next_attempt=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),key);
                worker.runBatch();
                if(Set.of("COMMITTED","FAILED").contains(store.receiptByKey(key).get("chainState")))break;
            }
            assertEquals(first,db.queryForObject("SELECT request_text FROM log_trust_delivery WHERE id=?",String.class,key));
            if(scenario.equals("timeout")) {
                assertNotEquals("COMMITTED",store.receiptByKey(key).get("chainState"));
                db.update("UPDATE log_trust_delivery SET deadline_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),key);
                db.update("UPDATE log_chain SET next_attempt=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),key);
                worker.runBatch();assertEquals("FAILED",store.receiptByKey(key).get("chainState"));
                assertEquals("RECONCILIATION_TIMEOUT",store.receiptByKey(key).get("lastError"));
            }else if(scenario.equals("action-required")) {
                assertEquals("FAILED",store.receiptByKey(key).get("chainState"));
                assertEquals("ACTION_REQUIRED",db.queryForObject("SELECT remote_status FROM log_trust_delivery WHERE id=?",String.class,key));
            }else assertEquals("COMMITTED",store.receiptByKey(key).get("chainState"),String.valueOf(store.receiptByKey(key).get("lastError")));
            mapper.writeValue(run.resolve("caller-state.json").toFile(),Map.of("scenario",scenario,"scope","LOCAL_SIMULATION_ONLY","callerPassed",true,"chainState",store.receiptByKey(key).get("chainState")));
        }catch(Throwable failure){callerFailure=failure;}
        finally {
            if(!server.waitFor(20,TimeUnit.SECONDS)){server.destroyForcibly();fail("Simulator did not settle");}
        }
        Process summarize=new ProcessBuilder(java,"-jar",home.resolve("trust-selfcheck.jar").toString(),"report","--input",report.toString(),"--out",run.resolve("summary").toString())
                .redirectErrorStream(true).redirectOutput(run.resolve("report-output.txt").toFile()).start();
        assertTrue(summarize.waitFor(10,TimeUnit.SECONDS));
        if(callerFailure!=null)throw new AssertionError("Actual AUDIT caller assertions failed; private run "+run.getFileName(),callerFailure);
        assertEquals(0,server.exitValue(),"Simulator checks failed: "+run.getFileName());
        assertEquals(0,summarize.exitValue(),"Report checks failed: "+run.getFileName());
    }
}
