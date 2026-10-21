package com.bproject.audit.service;

import com.bproject.audit.client.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Generic OPERATION_LOG delivery; disabled by default, exact per-scope routes and immutable snapshots. */
@Component @ConditionalOnProperty(name="audit.chain.mode",havingValue="generic",matchIfMissing=true)
public class GenericEvidenceWorker {
    private final LogStore store;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final TrustSources sources;
    private final GenericEvidenceHttp http;
    private final long maxWaitSeconds;
    public GenericEvidenceWorker(LogStore store,ObjectMapper mapper,
            @Value("${audit.chain.enabled:false}") boolean enabled,
            @Value("${audit.chain.sources-file:}") String file,
            @Value("${audit.chain.max-wait-seconds:86400}") long maxWaitSeconds) throws Exception {
        this.store=store;this.mapper=mapper;this.enabled=enabled;this.maxWaitSeconds=maxWaitSeconds;
        this.sources=new TrustSources(mapper,enabled?file:"");this.http=new GenericEvidenceHttp(mapper);
        if(enabled&&(file.isBlank()||maxWaitSeconds<1||maxWaitSeconds>604800))throw new IllegalArgumentException("Configure approved source matrix and bounded wait");
    }
    @Scheduled(fixedDelayString="${audit.chain.interval-ms:5000}")
    public void runBatch() {
        if(!enabled)return;
        for(var route:sources.active()) {
            var rows=store.db.queryForList("SELECT e.*,c.attempts,c.transaction_id,c.last_error FROM log_event e JOIN log_chain c ON c.id=e.id WHERE e.domain_id=? AND e.zone_id=? AND e.app_code=? AND e.received_at>=? AND c.state IN ('NOT_CONFIGURED','PENDING','ACCEPTED','SENDING') AND c.next_attempt<=? ORDER BY e.received_at LIMIT 20",
                    route.domainId(),route.zoneId(),route.appCode(),Timestamp.from(Instant.parse(route.eligibleFrom())),Timestamp.from(Instant.now()));
            for(var row:rows)deliver(row,route);
        }
    }
    private String claim(String id,boolean manual) {
        String lease=UUID.randomUUID().toString();
        String states=manual?"'FAILED'":"'NOT_CONFIGURED','PENDING','ACCEPTED','SENDING'";
        int claimed=manual?store.db.update("UPDATE log_chain SET state='SENDING',lease_id=?,next_attempt=? WHERE id=? AND state='FAILED' AND lease_id IS NULL",lease,Timestamp.from(Instant.now().plusSeconds(60)),id)
                :store.db.update("UPDATE log_chain SET state='SENDING',lease_id=?,next_attempt=? WHERE id=? AND state IN ("+states+") AND next_attempt<=?",lease,Timestamp.from(Instant.now().plusSeconds(60)),id,Timestamp.from(Instant.now()));
        return claimed==1?lease:null;
    }
    private void deliver(Map<String,Object> row,TrustSources.Route route) {
        String id=(String)row.get("id"),lease=claim(id,false);if(lease==null)return;
        int attempt=((Number)row.get("attempts")).intValue()+1;
        try {
            var snapshot=snapshot(row,route);
            var capability=http.call(route,"/capabilities",null);
            if(capability.status()!=200){accept(id,lease,route,snapshot,capability,attempt);return;}
            if(!route.instanceId().equals(capability.body().path("instanceId").asText())
                    ||!capability.body().path("isolationEnabled").asBoolean()
                    ||!capability.body().path("ingressSchemaVersions").isArray()
                    ||!java.util.stream.StreamSupport.stream(capability.body().path("ingressSchemaVersions").spliterator(),false).anyMatch(v->v.asInt()==1))throw new IntegrityFailure("TARGET_INSTANCE_OR_ISOLATION_DRIFT");
            if(instant(snapshot.get("deadline_at")).isBefore(Instant.now())){stop(id,lease,"RECONCILIATION_TIMEOUT");return;}
            String remote=(String)snapshot.get("remote_id");
            boolean bySource=remote==null||!Boolean.TRUE.equals(snapshot.get("source_reconciled"));
            boolean firstAttempt=!Boolean.TRUE.equals(snapshot.get("submission_attempted"));
            GenericEvidenceHttp.Reply reply;
            if(firstAttempt) {
                // Persist the intent BEFORE HTTP. A process crash here recovers by source number.
                int owns=store.db.update("UPDATE log_trust_delivery SET submission_attempted=TRUE,outcome_unknown=TRUE WHERE id=? AND EXISTS(SELECT 1 FROM log_chain c WHERE c.id=? AND c.lease_id=?)",id,id,lease);
                if(owns!=1)return;
                reply=http.call(route,"",(String)snapshot.get("request_text"));
            }else reply=http.call(route,bySource?"/by-source-event/"+id:"/"+remote,null);
            boolean foundBySource=!firstAttempt&&bySource&&reply.status()==200;
            if(!firstAttempt&&reply.status()==404) {
                // Never POST after a known receipt disappears or a disabled ingress / ambiguous 404.
                if(remote!=null||!"RECEIPT_NOT_FOUND".equals(reply.body().path("code").asText())){stop(id,lease,"TRUST_RECEIPT_NOT_FOUND_OR_DISABLED");return;}
                reply=http.call(route,"",(String)snapshot.get("request_text"));
            }
            accept(id,lease,route,snapshot,reply,attempt);
            if(foundBySource)store.db.update("UPDATE log_trust_delivery SET source_reconciled=TRUE WHERE id=? AND remote_id IS NOT NULL",id);
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();fail(id,lease,attempt,"TRUST_INTERRUPTED",false);}
        catch(IntegrityFailure invalid){stop(id,lease,invalid.getMessage());}
        catch(Exception unavailable){fail(id,lease,attempt,"TRUST_UNAVAILABLE_OR_INVALID_REPLY",false);}
    }
    private Map<String,Object> snapshot(Map<String,Object> row,TrustSources.Route route) throws Exception {
        String id=(String)row.get("id");
        var existing=store.db.queryForList("SELECT * FROM log_trust_delivery WHERE id=?",id);
        if(existing.isEmpty()) {
            if(row.get("transaction_id")!=null||((Number)row.get("attempts")).intValue()>0||row.get("last_error")!=null)
                throw new IntegrityFailure("LEGACY_OR_UNKNOWN_HISTORY_REQUIRES_RECONCILIATION");
            String request=GenericEvidenceMapping.request(mapper,id,(String)row.get("payload"),(String)row.get("payload_hash"));
            store.db.update("INSERT INTO log_trust_delivery(id,route_id,route_fingerprint,request_text,original_sha256,created_at,deadline_at) VALUES(?,?,?,?,?,?,?)",
                    id,route.id(),route.fingerprint(),request,CanonicalJson.hash(request),Timestamp.from(Instant.now()),Timestamp.from(Instant.now().plusSeconds(maxWaitSeconds)));
            existing=store.db.queryForList("SELECT * FROM log_trust_delivery WHERE id=?",id);
        }
        var value=existing.get(0);
        if(!route.id().equals(value.get("route_id"))||!route.fingerprint().equals(value.get("route_fingerprint")))throw new IntegrityFailure("SOURCE_ROUTE_DRIFT");
        if(!CanonicalJson.hash((String)value.get("request_text")).equals(value.get("original_sha256")))throw new IntegrityFailure("LOCAL_SNAPSHOT_DRIFT");
        return value;
    }
    private void accept(String id,String lease,TrustSources.Route route,Map<String,Object> snapshot,GenericEvidenceHttp.Reply reply,int attempt) throws Exception {
        store.db.update("INSERT INTO log_trust_receipt(receipt_id,event_key,raw_receipt,observed_at) VALUES(?,?,?,?)",UUID.randomUUID().toString(),id,reply.raw(),Timestamp.from(Instant.now()));
        if(reply.status()<200||reply.status()>=300){
            // 401/403/409/etc stop; 429/5xx back off and reconcile by original number, not blind POST.
            fail(id,lease,attempt,"TRUST_HTTP_"+reply.status(),reply.status()>=400&&reply.status()<500&&reply.status()!=429);return;
        }
        JsonNode body=reply.body();
        validate(id,route,snapshot,body);
        String status=body.path("status").asText();
        String projected=status.equals("COMMITTED")?"COMMITTED":status.equals("ACTION_REQUIRED")?"FAILED":"ACCEPTED";
        String error=status.equals("ACTION_REQUIRED")?"TRUST_ACTION_REQUIRED":null;
        store.db.update("UPDATE log_trust_delivery SET remote_id=?,remote_status=?,remote_event_sha256=?,outcome_unknown=?,receipt_raw=? WHERE id=? AND EXISTS(SELECT 1 FROM log_chain c WHERE c.id=? AND c.lease_id=?)",
                body.path("id").asText(),status,body.path("eventSha256").asText(),body.path("outcomeUnknown").asBoolean(),reply.raw(),id,id,lease);
        store.db.update("UPDATE log_chain SET state=?,attempts=0,transaction_id=?,last_error=?,lease_id=NULL,next_attempt=? WHERE id=? AND lease_id=?",
                projected,body.path("txId").isTextual()?body.path("txId").asText():null,error,Timestamp.from(Instant.now().plusSeconds(5)),id,lease);
    }
    private void validate(String id,TrustSources.Route route,Map<String,Object> snapshot,JsonNode body) throws Exception {
        for(String field:new String[]{"id","sourceSystem","sourceEventId","rootId","status","taskState","fileState","chainState","eventSha256","originalSha256","supersedesId","originalCid","manifestCid","manifestSha256","txId","errorCode","version","blockNumber","outcomeUnknown","nextRetryAt"})
            if(!body.has(field))throw new IntegrityFailure("INCOMPLETE_TRUST_RECEIPT");
        try{UUID.fromString(body.path("id").asText());}catch(Exception e){throw new IntegrityFailure("INVALID_TRUST_ID");}
        if(!id.equals(body.path("sourceEventId").asText())||!route.sourceSystem().equals(body.path("sourceSystem").asText())
                ||!snapshot.get("original_sha256").equals(body.path("originalSha256").asText())
                ||snapshot.get("remote_id")!=null&&!snapshot.get("remote_id").equals(body.path("id").asText()))throw new IntegrityFailure("TRUST_IDENTITY_OR_ORIGINAL_HASH_DRIFT");
        if(body.path("version").asInt()!=1||!body.path("supersedesId").isNull()||!body.path("rootId").asText().equals(body.path("id").asText()))throw new IntegrityFailure("UNEXPECTED_CORRECTION_VERSION");
        String hash=GenericEvidenceMapping.eventHash(mapper,(String)snapshot.get("request_text"),body,route);
        if(!hash.equals(body.path("eventSha256").asText())||snapshot.get("remote_event_sha256")!=null&&!snapshot.get("remote_event_sha256").equals(hash))throw new IntegrityFailure("TRUST_CANONICAL_HASH_DRIFT");
        if(!Set.of("ACCEPTED","PROCESSING","RETRY_WAIT","CONFIRMING","ACTION_REQUIRED","COMMITTED").contains(body.path("status").asText())
                ||!Set.of("READY","RUNNING","FAILED","DONE").contains(body.path("taskState").asText())||!body.path("outcomeUnknown").isBoolean())throw new IntegrityFailure("INVALID_TRUST_STATE");
        if(body.path("status").asText().equals("COMMITTED")) {
            if(!body.path("taskState").asText().equals("DONE")||!body.path("fileState").asText().equals("STORED")||!body.path("chainState").asText().equals("COMMITTED")||body.path("outcomeUnknown").asBoolean()
                    ||body.path("txId").asText("").isBlank()||body.path("txId").asText().length()>192
                    ||body.path("originalCid").asText("").isBlank()||body.path("manifestCid").asText("").isBlank()
                    ||!body.path("manifestSha256").asText("").matches("[0-9a-f]{64}"))throw new IntegrityFailure("INCONSISTENT_COMMIT_RECEIPT");
        }
    }
    private void stop(String id,String lease,String code){fail(id,lease,30,code,true);}
    private void fail(String id,String lease,int attempts,String code,boolean permanent){
        store.db.update("UPDATE log_chain SET state=?,attempts=?,last_error=?,lease_id=NULL,next_attempt=? WHERE id=? AND lease_id=?",
                permanent||attempts>=30?"FAILED":"PENDING",attempts,code,Timestamp.from(Instant.now().plusSeconds(permanent?0:Math.min(300,1L<<Math.min(attempts,8)))),id,lease);
    }
    /** Explicit privileged operation. No polling code calls this method. */
    public void retry(ServiceIdentity client,String eventId,String app) {
        if(!client.can("RETRY"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"PERMISSION_DENIED");
        if(!enabled)throw new ResponseStatusException(HttpStatus.CONFLICT,"CHAIN_NOT_CONFIGURED");
        String id=store.key(client,eventId,app);store.receiptByKey(id);
        var rows=store.db.queryForList("SELECT e.*,c.attempts,c.last_error,c.transaction_id,c.state FROM log_event e JOIN log_chain c ON c.id=e.id WHERE e.id=?",id);
        var row=rows.get(0);var route=sources.find((String)row.get("domain_id"),(String)row.get("zone_id"),(String)row.get("app_code"));
        if(route==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"SOURCE_NOT_APPROVED");
        String code=String.valueOf(row.get("last_error"));
        if(code.contains("DRIFT")||code.contains("HISTORY")||code.contains("INCOMPLETE")||code.contains("INCONSISTENT")||code.contains("VERSION")||code.equals("TRUST_HTTP_409"))throw new ResponseStatusException(HttpStatus.CONFLICT,"MANUAL_INTEGRITY_REVIEW_REQUIRED");
        String lease=claim(id,true);if(lease==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"EVENT_NOT_RETRYABLE");
        try{
            var snap=snapshot(row,route);String remote=(String)snap.get("remote_id");
            if(remote!=null) {
                // Always query before explicit retry, including unknown transactions.
                var current=http.call(route,"/"+remote,null);
                if(current.status()!=200){accept(id,lease,route,snap,current,1);throw new ResponseStatusException(HttpStatus.CONFLICT,"TRUST_RECONCILIATION_REQUIRED");}
                validate(id,route,snap,current.body());
                if(current.body().path("status").asText().equals("COMMITTED")){accept(id,lease,route,snap,current,1);return;}
                if(current.body().path("taskState").asText().equals("FAILED")) {
                    if(!route.retryAllowed()){stop(id,lease,"TRUST_RETRY_NOT_GRANTED");throw new ResponseStatusException(HttpStatus.FORBIDDEN,"TRUST_RETRY_NOT_GRANTED");}
                    var retry=http.call(route,"/"+remote+"/retry","{}");accept(id,lease,route,snap,retry,1);
                    if(retry.status()!=200&&retry.status()!=202)throw new ResponseStatusException(HttpStatus.CONFLICT,"TRUST_RETRY_REQUIRES_REVIEW");
                } else accept(id,lease,route,snap,current,1);
            }else {
                store.db.update("UPDATE log_chain SET state='PENDING',attempts=0,last_error=NULL,lease_id=NULL,next_attempt=? WHERE id=? AND lease_id=?",Timestamp.from(Instant.now()),id,lease);
            }
            store.db.update("UPDATE log_trust_delivery SET deadline_at=? WHERE id=?",Timestamp.from(Instant.now().plusSeconds(maxWaitSeconds)),id);
        }catch(ResponseStatusException reject){throw reject;}
        catch(IntegrityFailure invalid){stop(id,lease,invalid.getMessage());throw new ResponseStatusException(HttpStatus.CONFLICT,"MANUAL_INTEGRITY_REVIEW_REQUIRED");}
        catch(Exception unavailable){if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();stop(id,lease,"EXPLICIT_RETRY_RESULT_UNKNOWN");throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"TRUST_RETRY_RESULT_UNKNOWN_RECONCILE_FIRST");}
    }
    private static class IntegrityFailure extends RuntimeException{IntegrityFailure(String code){super(code);}}
    private static Instant instant(Object value){return value instanceof Timestamp t?t.toInstant():value instanceof java.time.OffsetDateTime t?t.toInstant():(Instant)value;}
}
