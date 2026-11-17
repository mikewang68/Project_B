package com.bproject.ehm.publicdata;

import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Broker ack is allowed only after accept() returns (the database transaction has committed). */
public final class CommittedInboxStore {
    public final PublicDataConfig config; public final OwnerCatalog catalog; public final ObjectMapper json;
    final JdbcTemplate jdbc; final TransactionTemplate tx;
    public enum Accepted { NEW,DUPLICATE,QUARANTINED }
    public CommittedInboxStore(JdbcTemplate jdbc,PublicDataConfig config,OwnerCatalog catalog,ObjectMapper json){
        this.jdbc=jdbc;this.config=config;this.catalog=catalog;this.json=json;
        tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        tx.setTimeout(30);
    }
    /** Explicit migration command only; never invoked by web startup or merely reading status. */
    public void initialize(){
        String prefix="CREATE TABLE IF NOT EXISTS ";
        jdbc.execute(prefix+config.auxiliary("consumer")+" (source_id VARCHAR(64) PRIMARY KEY,binding_hash VARCHAR(64) NOT NULL)");
        jdbc.execute(prefix+config.auxiliary("inbox")+" (source_id VARCHAR(64) NOT NULL,event_id VARCHAR(192) NOT NULL,wire_sha256 VARCHAR(64) NOT NULL,event_json TEXT NOT NULL,raw_body TEXT NOT NULL,transport VARCHAR(8) NOT NULL,status VARCHAR(24) NOT NULL,attempts INTEGER NOT NULL,next_retry TIMESTAMP WITH TIME ZONE NOT NULL,error_code VARCHAR(64),received_at TIMESTAMP WITH TIME ZONE NOT NULL,PRIMARY KEY(source_id,event_id))");
        jdbc.execute(prefix+config.auxiliary("quarantine")+" (source_id VARCHAR(64) NOT NULL,body_sha256 VARCHAR(64) NOT NULL,raw_body TEXT NOT NULL,reason VARCHAR(64) NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL,PRIMARY KEY(source_id,body_sha256))");
        jdbc.execute(prefix+config.auxiliary("cursor")+" (source_id VARCHAR(64) NOT NULL,feed_hash VARCHAR(64) NOT NULL,cursor_value TEXT,PRIMARY KEY(source_id,feed_hash))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ehms_pd_pending_idx ON "+config.auxiliary("inbox")+" (source_id,status,next_retry)");
        // Bind an immutable source identity. Concurrent initializations must be serialized by the operator.
        var binding=jdbc.queryForList("SELECT binding_hash FROM "+config.auxiliary("consumer")+" WHERE source_id=?",String.class,config.sourceId());
        String hash=bindingHash();
        if(binding.isEmpty())jdbc.update("INSERT INTO "+config.auxiliary("consumer")+" VALUES (?,?)",config.sourceId(),hash);
        else if(!binding.get(0).equals(hash))throw new IllegalStateException("SOURCE_SCOPE_OR_CATALOG_CHANGED_USE_NEW_SOURCE_ID");
    }
    String bindingHash(){return hash((config.projectId()+"\n"+config.regionId()+"\nEHMS\n"+config.principal()+"\n"+config.policyRevision()+"\n"+config.httpBase()+"\nb-device-committed-v1\n"+catalog.fingerprint).getBytes(StandardCharsets.UTF_8));}
    void lock(){
        String binding=jdbc.queryForObject("SELECT binding_hash FROM "+config.auxiliary("consumer")+" WHERE source_id=? FOR UPDATE",String.class,config.sourceId());
        if(!bindingHash().equals(binding))throw new IllegalStateException("SOURCE_BINDING_MISMATCH");
    }
    public void verifyBinding(){tx.executeWithoutResult(s->lock());}
    public void quarantineTransport(byte[] raw,String reason){
        if(raw.length==0||raw.length>2*1024*1024||!Set.of("MQ_KEY_EVENT_ID_MISMATCH","MALFORMED_JSON").contains(reason))throw new IllegalArgumentException("INVALID_TRANSPORT_QUARANTINE");
        tx.executeWithoutResult(s->{lock();quarantine(raw,reason);});
    }
    public Accepted accept(byte[] raw,String transport,String feed,String cursor){
        if(!Set.of("HTTP","MQ").contains(transport)||raw.length==0||raw.length>2*1024*1024)throw new IllegalArgumentException("INVALID_MESSAGE_SIZE_OR_TRANSPORT");
        return tx.execute(s->{lock();Accepted result=acceptLocked(raw,transport);
            // Conflict stops HTTP progress; an operator must reconcile it, not silently skip a changed event.
            if(feed!=null&&result!=Accepted.QUARANTINED){
                if(cursor==null||cursor.isBlank()||cursor.length()>4096)throw new IllegalArgumentException("INVALID_CURSOR");
                if(jdbc.update("UPDATE "+config.auxiliary("cursor")+" SET cursor_value=? WHERE source_id=? AND feed_hash=?",cursor,config.sourceId(),feed)==0)
                    jdbc.update("INSERT INTO "+config.auxiliary("cursor")+" VALUES (?,?,?)",config.sourceId(),feed,cursor);
            }return result;});
    }
    private Accepted acceptLocked(byte[] raw,String transport){
        JsonNode root;
        try{root=json.readTree(raw);}catch(Exception invalid){return quarantine(raw,"MALFORMED_JSON");}
        if(root==null)return quarantine(raw,"MALFORMED_JSON");
        JsonNode event=root.path("event");String id=root.path("event_id").asText(),sha=root.path("wire_sha256").asText();
        boolean committed=transport.equals("MQ")?"b.device-committed.v1".equals(root.path("schema_version").asText())&&root.path("simulated").asBoolean():
            "COMMITTED".equals(root.path("state").asText())&&!root.path("broker_message_id").asText().isBlank();
        if(!committed||!id.matches("[a-zA-Z0-9_.:-]{1,192}")||!sha.matches("[a-fA-F0-9]{64}")
                ||!id.equals(event.path("event_id").asText())||!event.path("simulated").asBoolean()
                ||!"b.device-event.v1".equals(event.path("schema_version").asText()))return quarantine(raw,"ENVELOPE_CONTRACT_REJECTED");
        var existing=jdbc.query("SELECT wire_sha256,event_json FROM "+config.auxiliary("inbox")+" WHERE source_id=? AND event_id=?",
            (r,n)->List.of(r.getString(1),r.getString(2)),config.sourceId(),id);
        if(!existing.isEmpty()){
            try{if(existing.get(0).get(0).equalsIgnoreCase(sha)&&json.readTree(existing.get(0).get(1)).equals(event))return Accepted.DUPLICATE;}
            catch(Exception e){throw new IllegalStateException("CORRUPT_INBOX");}
            return quarantine(raw,"EVENT_ID_CONTENT_CONFLICT");
        }
        Long pending=jdbc.queryForObject("SELECT COUNT(*) FROM "+config.auxiliary("inbox")+" WHERE source_id=? AND status IN ('PENDING','FAILED')",Long.class,config.sourceId());
        if(pending!=null&&pending>=10000)throw new IllegalStateException("INBOX_BACKPRESSURE_PROCESS_OR_REPAIR_FIRST");
        jdbc.update("INSERT INTO "+config.auxiliary("inbox")+" VALUES (?,?,?,?,?,?,?,0,?,NULL,?)",config.sourceId(),id,sha.toLowerCase(Locale.ROOT),encode(event),
            Base64.getEncoder().encodeToString(raw),transport,"PENDING",Timestamp.from(Instant.EPOCH),Timestamp.from(Instant.now()));return Accepted.NEW;
    }
    Accepted quarantine(byte[] raw,String reason){
        String key=hash(raw);if(jdbc.queryForObject("SELECT COUNT(*) FROM "+config.auxiliary("quarantine")+" WHERE source_id=? AND body_sha256=?",Long.class,config.sourceId(),key)==0)
            jdbc.update("INSERT INTO "+config.auxiliary("quarantine")+" VALUES (?,?,?,?,?)",config.sourceId(),key,Base64.getEncoder().encodeToString(raw),reason,Timestamp.from(Instant.now()));
        return Accepted.QUARANTINED;
    }
    public String cursor(String feed){var rows=jdbc.queryForList("SELECT cursor_value FROM "+config.auxiliary("cursor")+" WHERE source_id=? AND feed_hash=?",String.class,config.sourceId(),feed);return rows.isEmpty()?null:rows.get(0);}
    public int process(int limit){
        if(limit<1||limit>1000)throw new IllegalArgumentException("BOUNDED_BATCH_REQUIRED");int done=0;
        var ids=jdbc.queryForList("SELECT event_id FROM "+config.auxiliary("inbox")+" WHERE source_id=? AND status IN ('PENDING','FAILED') AND attempts<10 AND next_retry<=? ORDER BY received_at,event_id LIMIT ?",String.class,config.sourceId(),Timestamp.from(Instant.now()),limit);
        for(String id:ids){
            try{Boolean applied=tx.execute(s->{lock();var row=jdbc.queryForMap("SELECT event_json,status,attempts FROM "+config.auxiliary("inbox")+" WHERE source_id=? AND event_id=? FOR UPDATE",config.sourceId(),id);
                if(!Set.of("PENDING","FAILED").contains((String)row.get("status")))return false;
                JsonNode event;try{event=json.readTree((String)row.get("event_json"));}catch(Exception ex){throw new IllegalArgumentException("CORRUPT_INBOX");}
                String status=new PublicBusinessMapper(this).apply(event);
                jdbc.update("UPDATE "+config.auxiliary("inbox")+" SET status=?,attempts=attempts+1,error_code=NULL WHERE source_id=? AND event_id=?",status,config.sourceId(),id);return true;});
                if(Boolean.TRUE.equals(applied))done++;
            }catch(Exception failure){
                // Separate transaction AFTER all partial business writes have rolled back. No payload/SQL/credential exception text.
                tx.executeWithoutResult(s->{lock();jdbc.update("UPDATE "+config.auxiliary("inbox")+" SET status='FAILED',attempts=attempts+1,next_retry=?,error_code=? WHERE source_id=? AND event_id=? AND status IN ('PENDING','FAILED')",
                    Timestamp.from(Instant.now().plusSeconds(60)),failure instanceof IllegalArgumentException?"BUSINESS_CONTRACT_REJECTED":"BUSINESS_TRANSACTION_FAILED",config.sourceId(),id);});
            }
        }return done;
    }
    public void retryFailed(){tx.executeWithoutResult(s->{lock();jdbc.update("UPDATE "+config.auxiliary("inbox")+" SET attempts=0,next_retry=? WHERE source_id=? AND status='FAILED'",Timestamp.from(Instant.EPOCH),config.sourceId());});}
    public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();result.put("schema","EHMS_PUBLIC_DATA_STATUS_V1");result.put("projectId",config.projectId());result.put("regionId",config.regionId());
        result.put("inbox",jdbc.queryForList("SELECT status,COUNT(*) AS total FROM "+config.auxiliary("inbox")+" WHERE source_id=? GROUP BY status",config.sourceId()));
        result.put("quarantine",jdbc.queryForObject("SELECT COUNT(*) FROM "+config.auxiliary("quarantine")+" WHERE source_id=?",Long.class,config.sourceId()));
        result.put("sharedGeneratorControlled",false);result.put("credentialsIncluded",false);return result;
    }
    String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("JSON_ENCODING_FAILED");}}
    public static String hash(byte[] raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}catch(Exception ex){throw new IllegalStateException(ex);}}
}
