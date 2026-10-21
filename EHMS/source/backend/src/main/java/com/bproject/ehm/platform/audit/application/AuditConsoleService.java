package com.bproject.ehm.platform.audit.application;

import com.bproject.ehm.platform.audit.adapter.in.web.IamAuditIdentityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;

/** Read-only EHM journal browser. Never exposes service credentials or accepts client scope overrides. */
@Service @Profile("server")
public class AuditConsoleService {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final String table, endpoint, readerFile, domain, zone;
    private final Set<String> readers;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public AuditConsoleService(JdbcTemplate db,ObjectMapper mapper,
            @Value("${ehm.persistence.schema:ehm}") String schema,
            @Value("${ehm.audit.query-actors:}") String readers,
            @Value("${ehm.audit.endpoint:}") String endpoint,
            @Value("${ehm.audit.reader-token-file:}") String readerFile,
            @Value("${ehm.audit.domain:unassigned}") String domain,@Value("${ehm.audit.zone:unassigned}") String zone,
            @Value("${ehm.audit.outbox-table:audit_outbox}") String queueTable) {
        if(!schema.matches("[A-Za-z_][A-Za-z0-9_]*")||!queueTable.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("Invalid audit table");
        this.db=db;this.mapper=mapper;this.table=schema+"."+queueTable;this.endpoint=endpoint;this.readerFile=readerFile;this.domain=domain;this.zone=zone;
        this.readers=new HashSet<>();for(String id:readers.split(","))if(!id.isBlank())this.readers.add(id.trim());
    }
    public boolean all(HttpServletRequest request){var actor=IamAuditIdentityFilter.actor(request);return com.bproject.ehm.platform.security.BusinessPermissionFilter.admin(request)||actor!=null&&readers.contains(actor.getName());}
    private String actor(HttpServletRequest request){var p=IamAuditIdentityFilter.actor(request);if(p==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先通过IAM验证身份，再查看持久化日志");return p.getName();}
    public Map<String,Object> session(HttpServletRequest request){
        var p=IamAuditIdentityFilter.actor(request);return Map.of("identityVerified",p!=null,"userId",p==null?"":p.getName(),
                "readOnly",IamAuditIdentityFilter.readOnly(request),"queryScope",all(request)?"LOCAL_EHM_MODULE":"OWN_EVENTS",
                "receiptLookupConfigured",!endpoint.isBlank()&&!readerFile.isBlank());
    }
    public Map<String,Object> query(HttpServletRequest request,int page,int size,String action,String outcome,String delivery,String userId,String eventId,String traceId,String from,String to){
        String subject=actor(request);
        if(page<0||page>10000||size<1||size>100)bad();
        var args=new ArrayList<Object>();StringBuilder where=new StringBuilder(" WHERE 1=1");
        match(where,args,"appCode","ehealth");match(where,args,"domainId",domain);match(where,args,"zoneId",zone);
        if(!all(request)){
            if(userId!=null&&!userId.isBlank()&&!subject.equals(userId))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"不能查询其他用户日志");
            match(where,args,"userId",subject);
        }else match(where,args,"userId",userId);
        match(where,args,"action",action);match(where,args,"outcome",outcome);match(where,args,"traceId",traceId);
        if(eventId!=null&&!eventId.isBlank()){bounded(eventId);where.append(" AND event_id=?");args.add(eventId);}
        if(delivery!=null&&!delivery.isBlank()){
            if(!Set.of("PENDING","SENDING","ACCEPTED","FAILED").contains(delivery))bad();
            where.append(" AND state=?");args.add(delivery);
        }
        Instant lower=date(from),upper=date(to);if(lower!=null&&upper!=null&&lower.isAfter(upper))bad();
        if(lower!=null){where.append(" AND created_at>=?");args.add(Timestamp.from(lower));}
        if(upper!=null){where.append(" AND created_at<=?");args.add(Timestamp.from(upper));}
        Long total=db.queryForObject("SELECT COUNT(*) FROM "+table+where,Long.class,args.toArray());
        var paging=new ArrayList<>(args);paging.add(size);paging.add(page*size);
        var items=db.queryForList("SELECT event_id,payload,payload_hash,state,attempts,last_error,created_at FROM "+table+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",paging.toArray()).stream().map(this::view).toList();
        return Map.of("items",items,"total",total,"page",page,"size",size,"scope",all(request)?"LOCAL_EHM_MODULE":"OWN_EVENTS","timeBasis","LOCAL_ENQUEUED_AT");
    }
    public Map<String,Object> detail(HttpServletRequest request,String id){
        var result=query(request,0,1,null,null,null,null,id,null,null,null);
        var rows=(List<?>)result.get("items");if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"日志不存在或不在当前用户范围内");
        return (Map<String,Object>)rows.get(0);
    }
    public Map<String,Object> receipt(HttpServletRequest request,String id){
        var local=detail(request,id);
        if(endpoint.isBlank()||readerFile.isBlank())return Map.of("eventId",id,"lookupState","NOT_CONFIGURED","chainState","UNKNOWN","message","尚未配置公共日志只读凭据；本地入队或ACCEPTED不代表已上链");
        try{
            URI base=URI.create(endpoint);String path=base.getPath();
            if(!Set.of("http","https").contains(base.getScheme())||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!path.endsWith("/events"))throw new IllegalStateException();
            URI target=URI.create(endpoint+"/"+java.net.URLEncoder.encode(id,java.nio.charset.StandardCharsets.UTF_8)+"/receipt?appCode=ehealth");
            String token=Files.readString(Path.of(readerFile)).trim();
            var response=http.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(4)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)return Map.of("eventId",id,"lookupState",response.statusCode()==404?"NOT_RECEIVED":"UNAVAILABLE","chainState","UNKNOWN","upstreamStatus",response.statusCode());
            var body=mapper.readTree(response.body());
            if(!id.equals(body.path("eventId").asText())||!"ehealth".equals(body.path("appCode").asText())
                    ||!body.path("stored").isBoolean()||!body.path("stored").booleanValue()
                    ||!local.get("payloadHash").equals(body.path("payloadHash").asText()))throw new IllegalStateException();
            String state=body.path("chainState").asText("UNKNOWN"),tx=body.path("transactionId").asText("");
            if(!Set.of("PENDING","SENDING","ACCEPTED","COMMITTED","FAILED","NOT_CONFIGURED","UNKNOWN").contains(state))state="UNKNOWN";
            // Never call a malformed COMMITTED receipt confirmed.
            if(state.equals("COMMITTED")&&tx.isBlank())throw new IllegalStateException();
            return Map.of("eventId",id,"lookupState","VERIFIED_PUBLIC_RECEIPT","chainState",state,"transactionId",tx,"payloadHash",body.path("payloadHash").asText(),"queriedAt",Instant.now().toString());
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"回执查询中断，结果未知");}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"公共回执不可用或核验失败，不能认定已上链");}
    }
    private Map<String,Object> view(Map<String,Object> row){
        try{
            var data=mapper.readTree((String)row.get("payload"));var result=new LinkedHashMap<String,Object>();
            for(String key:List.of("eventId","occurredAt","appCode","domainId","zoneId","userId","actorTrust","menuId","action","objectId","outcome","method","path","traceId","sourceKind","summary"))result.put(key,data.path(key).asText());
            result.put("durationMs",data.path("durationMs").asLong());result.put("payloadHash",row.get("payload_hash"));result.put("state",row.get("state"));result.put("attempts",row.get("attempts"));result.put("errorCode",row.get("last_error")==null?"":row.get("last_error"));
            Object time=row.get("created_at");
            result.put("enqueuedAt",time instanceof Timestamp t?t.toInstant().toString():time instanceof java.time.OffsetDateTime t?t.toInstant().toString():String.valueOf(time));return result;
        }catch(Exception e){throw new IllegalStateException("Invalid stored event",e);}
    }
    private void match(StringBuilder where,List<Object> args,String field,String value){
        if(value==null||value.isBlank())return;bounded(value);
        try{
            String fragment="\""+field+"\":"+mapper.writeValueAsString(value);
            where.append(" AND payload LIKE ? ESCAPE '!'");args.add("%"+fragment.replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        }catch(java.io.IOException e){throw new IllegalArgumentException(e);}
    }
    private static void bounded(String value){if(value.length()>192||value.chars().anyMatch(Character::isISOControl))bad();}
    private static Instant date(String text){try{return text==null||text.isBlank()?null:Instant.parse(text);}catch(Exception e){bad();return null;}}
    private static void bad(){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"日志筛选参数无效");}
}
