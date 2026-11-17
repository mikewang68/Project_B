package com.bproject.ehm.publicdata;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommittedConsumerTest {
    final ObjectMapper json=PublicDataMain.JSON;JdbcTemplate jdbc;CommittedInboxStore store;PublicDataConfig config;OwnerCatalog catalog;
    final String device="SIM-TEST01",point="EHM-PT-1";final long now=Instant.now().toEpochMilli();
    @BeforeEach void init()throws Exception{setup("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");}
    void setup(String url)throws Exception{
        config=new PublicDataConfig(true,url,"sa",Path.of("unused"),"ehm","aggregate_store","ehms-public-v1","PROJECT_B","EHMS_DEMO","b-sim-ehms-reader-v1","allocation-v1","http://127.0.0.1:38121",Path.of("unused"),Path.of("unused"),Path.of("unused"));
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS ehm");jdbc.execute("CREATE TABLE IF NOT EXISTS ehm.aggregate_store(project_id VARCHAR(64),region_id VARCHAR(64),subsystem_code VARCHAR(64),aggregate_type VARCHAR(64),aggregate_id VARCHAR(192),payload TEXT,updated_at TIMESTAMP WITH TIME ZONE,PRIMARY KEY(project_id,region_id,subsystem_code,aggregate_type,aggregate_id))");
        catalog=new OwnerCatalog(json.readTree("""
            {"schema_version":"b.device-catalog.v1","simulated":true,"devices":[{"device_id":"SIM-TEST01","name":"测试设备","fields":[
              {"point_code":"EHM-PT-1","name":"电流","unit":"A","provenance":{"wire_type":"f32"}},
              {"point_code":"EHM-PT-2","name":"故障代码","unit":null,"provenance":{"wire_type":"ascii32"}},
              {"point_code":"EHM-PT-3","name":"波形","unit":null,"missing_mapping":true,"provenance":{}}]}]}
            """),json.readTree("""
            {"system":"EHMS","tables":{"设备采集点":[{"点位编号":"EHM-PT-1","原始点消费系统":"EHMS"},{"点位编号":"EHM-PT-2","原始点消费系统":"EHMS"},{"点位编号":"EHM-PT-3","原始点消费系统":"EHMS"}]}}
            """),json);
        store=new CommittedInboxStore(jdbc,config,catalog,json);store.initialize();
    }
    ObjectNode event(String id,long at,String quality,Object value){
        var n=json.createObjectNode();n.put("schema_version","b.device-event.v1");n.put("simulated",true);n.put("event_id",id);n.put("device_id",device);n.put("source_ts_ms",at);n.put("observed_ts_ms",at);
        var p=n.putObject("payload").putArray("points").addObject();p.put("point_code",point);p.set("value",json.valueToTree(value));p.put("unit","A");if(quality==null)p.putNull("quality");else p.put("quality",quality);return n;
    }
    byte[] envelope(ObjectNode event,String transport)throws Exception{
        var n=json.createObjectNode();n.put("schema_version","b.device-committed.v1");n.put("simulated",true);n.put("event_id",event.path("event_id").asText());n.put("wire_sha256","a".repeat(64));
        n.set("event",event);if(transport.equals("HTTP")){n.put("state","COMMITTED");n.put("broker_message_id","broker-1");}return json.writeValueAsBytes(n);
    }
    void apply(ObjectNode e)throws Exception{store.accept(envelope(e,"MQ"),"MQ",null,null);store.process(100);}
    JsonNode aggregate(String type,String id)throws Exception{return json.readTree(jdbc.queryForObject("SELECT payload FROM ehm.aggregate_store WHERE aggregate_type=? AND aggregate_id=?",String.class,type,id));}
    long count(String type){return jdbc.queryForObject("SELECT COUNT(*) FROM ehm.aggregate_store WHERE aggregate_type=?",Long.class,type);}
    String status(String id){return jdbc.queryForObject("SELECT status FROM ehm.ehms_pd_inbox WHERE event_id=?",String.class,id);}
    @Test void ackAfterDurableInboxBeforeBusinessProcessing()throws Exception{
        assertEquals(CommittedInboxStore.Accepted.NEW,store.accept(envelope(event("e1",now,"GOOD",12),"MQ"),"MQ",null,null));
        assertEquals("PENDING",status("e1"));assertEquals(0,count("asset"));
        assertEquals(1,store.process(10));assertEquals("APPLIED",status("e1"));assertEquals(1,count("asset"));assertEquals(1,count("component"));assertEquals(2,count("measurement-point"));assertEquals(1,count("committed-telemetry"));
        assertTrue(aggregate("asset",OwnerCatalog.asset(device)).path("name").asText().contains("公共协议模拟"));
        assertTrue(aggregate("device-snapshot",OwnerCatalog.asset(device)).path("health").isNull());
        assertEquals(0,count("health-assessment"));assertEquals(0,count("work-order"));
    }
    @Test void duplicateAcrossHttpMqAndRestartIsIdempotent()throws Exception{
        var e=event("e1",now,"GOOD",12);apply(e);
        store=new CommittedInboxStore(jdbc,config,catalog,json);
        assertEquals(CommittedInboxStore.Accepted.DUPLICATE,store.accept(envelope(e,"HTTP"),"HTTP","f","c1"));
        assertEquals(0,store.process(10));assertEquals(1,count("committed-observation"));assertEquals("c1",store.cursor("f"));
    }
    @Test void sourceIdentityConflictQuarantinedWithoutOverwriteOrCursorAdvance()throws Exception{
        var e=event("e1",now,"GOOD",12);store.accept(envelope(e,"HTTP"),"HTTP","f","c1");
        var changed=event("e1",now,"GOOD",99);
        assertEquals(CommittedInboxStore.Accepted.QUARANTINED,store.accept(envelope(changed,"HTTP"),"HTTP","f","c2"));
        assertEquals("c1",store.cursor("f"));store.process(1);
        assertEquals(12,aggregate("committed-latest",OwnerCatalog.point(device,point)).path("rawPoint").path("value").asInt());
        assertEquals(1L,store.status().get("quarantine"));
    }
    @Test void invalidEnvelopeAndMalformedJsonDurablyQuarantined()throws Exception{
        assertEquals(CommittedInboxStore.Accepted.QUARANTINED,store.accept("not-json".getBytes(),"MQ",null,null));
        var e=event("e1",now,"GOOD",2);e.put("simulated",false);
        assertEquals(CommittedInboxStore.Accepted.QUARANTINED,store.accept(envelope(e,"MQ"),"MQ",null,null));assertEquals(0,store.process(10));
    }
    @Test void wireShaIsNotRecomputedFromReserializedJson()throws Exception{
        byte[] raw=envelope(event("e1",now,"GOOD",1),"MQ");assertNotEquals("a".repeat(64),CommittedInboxStore.hash(raw));
        store.accept(raw,"MQ",null,null);assertEquals("a".repeat(64),jdbc.queryForObject("SELECT wire_sha256 FROM ehm.ehms_pd_inbox",String.class));
        assertArrayEquals(raw,Base64.getDecoder().decode(jdbc.queryForObject("SELECT raw_body FROM ehm.ehms_pd_inbox",String.class)));
    }
    @Test void unknownDeviceIsRetainedButCannotCreateAsset()throws Exception{var e=event("e1",now,"GOOD",1);e.put("device_id","SIM-OTHER");apply(e);assertEquals("IGNORED",status("e1"));assertEquals(0,count("asset"));}
    @Test void incomingScopeCannotOverrideLocalScope()throws Exception{
        var e=event("e1",now,"GOOD",1);e.put("project_id","OTHER");e.put("region_id","SHEN_TIE");apply(e);
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM ehm.aggregate_store WHERE project_id<>'PROJECT_B' OR region_id<>'EHMS_DEMO' OR subsystem_code<>'EHMS'",Long.class));
    }
    @Test void nullUnknownAndBadQualityCannotBecomeNormal()throws Exception{
        for(String q:new String[]{null,"UNRECOGNIZED","BAD","STALE","UNCERTAIN"}){
            String id="e"+Objects.toString(q,"null");apply(event(id,now+count("committed-observation"),q,13));
            var observed=aggregate("committed-latest",OwnerCatalog.point(device,point));assertEquals(13,observed.path("rawPoint").path("value").asInt());
            var quality=aggregate("point-quality",OwnerCatalog.point(device,point));assertNotEquals("GOOD",quality.path("status").asText());assertTrue(quality.path("value").isNull());
        }
    }
    @Test void missingValueIsNeverZeroAndStringNeverCoerced()throws Exception{
        apply(event("e1",now,"GOOD",null));assertTrue(aggregate("committed-latest",OwnerCatalog.point(device,point)).path("rawPoint").path("value").isNull());
        apply(event("e2",now+1,"GOOD","12"));assertTrue(aggregate("point-quality",OwnerCatalog.point(device,point)).path("value").isNull());
    }
    @Test void unitMismatchIsRetainedButNotEvaluated()throws Exception{
        var e=event("e1",now,"GOOD",12);((ObjectNode)e.path("payload").path("points").get(0)).put("unit","mA");apply(e);
        assertTrue(aggregate("point-quality",OwnerCatalog.point(device,point)).path("value").isNull());
        assertEquals("mA",aggregate("committed-latest",OwnerCatalog.point(device,point)).path("rawPoint").path("unit").asText());
    }
    @Test void lateEventRecordedButLatestNeverRegresses()throws Exception{
        apply(event("e2",now,"GOOD",2));apply(event("e1",now-60000,"GOOD",1));
        assertEquals(2,count("committed-telemetry"));assertEquals("e2",aggregate("committed-latest",OwnerCatalog.point(device,point)).path("eventId").asText());
    }
    @Test void missingSourceTimestampNotForgedAndCannotReplaceSourcedLatest()throws Exception{
        apply(event("e1",now,"GOOD",1));var e=event("e2",now+1,"GOOD",2);e.putNull("source_ts_ms");apply(e);
        assertEquals("e1",aggregate("committed-latest",OwnerCatalog.point(device,point)).path("eventId").asText());
    }
    @Test void failedBusinessTransactionRollsBackPartialWritesAndCanRetry()throws Exception{
        jdbc.execute("ALTER TABLE ehm.aggregate_store ADD CONSTRAINT fail_points CHECK(aggregate_type<>'measurement-point')");
        apply(event("e1",now,"GOOD",1));assertEquals("FAILED",status("e1"));assertEquals(0,count("asset"));assertEquals(0,count("committed-observation"));
        jdbc.execute("ALTER TABLE ehm.aggregate_store DROP CONSTRAINT fail_points");store.retryFailed();assertEquals(1,store.process(10));assertEquals("APPLIED",status("e1"));assertEquals(1,count("asset"));
    }
    @Test void cursorUpdateAndInboxRollbackTogetherIfCursorInvalid()throws Exception{
        assertThrows(IllegalArgumentException.class,()->store.accept(envelope(event("e1",now,"GOOD",1),"HTTP"),"HTTP","f",""));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM ehm.ehms_pd_inbox",Long.class));assertNull(store.cursor("f"));
    }
    @Test void duplicatePointFailsSafelyInsteadOfPartialWrite()throws Exception{
        var e=event("e1",now,"GOOD",1);var points=(ArrayNode)e.path("payload").path("points");points.add(points.get(0).deepCopy());apply(e);assertEquals("FAILED",status("e1"));assertEquals(0,count("asset"));
    }
    @Test void missingManufacturerMappingOnlyPreservesObservation()throws Exception{
        var e=event("e1",now,"GOOD",1);((ObjectNode)e.path("payload").path("points").get(0)).put("point_code","EHM-PT-3");apply(e);
        assertEquals(1,count("committed-observation"));assertEquals(2,count("measurement-point"));assertEquals(0,count("committed-telemetry"));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM ehm.aggregate_store WHERE aggregate_type='measurement-point' AND aggregate_id=?",Long.class,OwnerCatalog.point(device,"EHM-PT-3")));
    }
    @Test void stringsAndAssetReferencesRetainedWithoutFakeScalarOrFft()throws Exception{
        var e=event("e1",now,"GOOD",List.of(1,2,3));var p=(ObjectNode)e.path("payload").path("points").get(0);p.put("point_code","EHM-PT-2");p.putNull("unit");p.putObject("asset_ref").put("sha256","b".repeat(64));apply(e);
        assertEquals(0,count("committed-telemetry"));assertTrue(aggregate("committed-latest",OwnerCatalog.point(device,"EHM-PT-2")).path("rawPoint").path("value").isArray());
        assertFalse(aggregate("measurement-point",OwnerCatalog.point(device,"EHM-PT-2")).path("enabled").asBoolean());
    }
    @Test void persistentBadQualityCreatesOneIncidentNotAlarmPerSample()throws Exception{
        apply(event("e1",now,"BAD",1));apply(event("e2",now+1,"BAD",1));assertEquals(1,count("alarm"));
        apply(event("e3",now+2,"GOOD",1));apply(event("e4",now+3,"BAD",1));assertEquals(2,count("alarm"));
    }
    @Test void sourceBindingRejectsCatalogAndScopeChanges()throws Exception{
        jdbc.update("UPDATE ehm.ehms_pd_consumer SET binding_hash=?","0".repeat(64));
        assertThrows(IllegalStateException.class,()->store.accept(envelope(event("e1",now,"GOOD",1),"MQ"),"MQ",null,null));
        assertThrows(IllegalStateException.class,store::initialize);
    }
    @Test void defaultsOffAndInvalidScopeEndpointRejected()throws Exception{
        assertThrows(IllegalArgumentException.class,()->new PublicDataConfig(false,config.jdbcUrl(),"sa",Path.of("x"),"ehm","aggregate_store","s","PROJECT_B","UNASSIGNED","p","v","http://127.0.0.1:38121",Path.of("x"),Path.of("x"),Path.of("x")));
        assertThrows(IllegalArgumentException.class,()->new PublicDataConfig(false,config.jdbcUrl(),"sa",Path.of("x"),"ehm","aggregate_store","s","PROJECT_B","EHMS_DEMO","p","v","http://example.com:80",Path.of("x"),Path.of("x"),Path.of("x")));
    }
    @Test void historyReceiptValidationAndEmptyPageCursorRetention()throws Exception{
        byte[] receipt=envelope(event("e1",now,"GOOD",1),"HTTP");
        String page="{\"items\":[{\"event_id\":\"e1\",\"wire_sha256\":\""+"a".repeat(64)+"\",\"cursor\":\"opaque-cursor\"}],\"has_more\":false}";
        var urls=new ArrayList<String>();var reader=new CommittedHttpReader(store,path->{urls.add(path);return path.contains("/events?")?receipt:page.getBytes();});
        assertEquals(1,reader.page(device,1));assertEquals(0,reader.page(device,1));assertTrue(urls.stream().anyMatch(u->u.contains("cursor=opaque-cursor")));
        var empty=new CommittedHttpReader(store,path->{assertTrue(path.contains("opaque-cursor"));return "{\"items\":[]}".getBytes();});assertEquals(0,empty.page(device,1));
        assertEquals("opaque-cursor",jdbc.queryForObject("SELECT cursor_value FROM ehm.ehms_pd_cursor",String.class));
    }
    @Test void mismatchedReceiptNeverAdvancesHistoryCursor()throws Exception{
        var wrong=event("e1",now,"GOOD",1);wrong.put("device_id","SIM-OTHER");byte[] receipt=envelope(wrong,"HTTP");
        var reader=new CommittedHttpReader(store,path->path.contains("/events?")?receipt:("{\"items\":[{\"event_id\":\"e1\",\"wire_sha256\":\""+"a".repeat(64)+"\",\"cursor\":\"c\"}]}").getBytes());
        assertThrows(IllegalArgumentException.class,()->reader.page(device,1));assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM ehm.ehms_pd_cursor",Long.class));
    }
    @Test void databaseReopenResumesDurableInboxAndCheckpoint(@TempDir Path dir)throws Exception{
        setup("jdbc:h2:file:"+dir.resolve("consumer").toString().replace('\\','/'));store.accept(envelope(event("e1",now,"GOOD",1),"HTTP"),"HTTP","feed","saved");
        var reopened=new CommittedInboxStore(new JdbcTemplate(new DriverManagerDataSource(config.jdbcUrl(),"sa","")),config,catalog,json);reopened.initialize();
        assertEquals("saved",reopened.cursor("feed"));assertEquals(1,reopened.process(1));assertEquals("APPLIED",status("e1"));
    }
}
