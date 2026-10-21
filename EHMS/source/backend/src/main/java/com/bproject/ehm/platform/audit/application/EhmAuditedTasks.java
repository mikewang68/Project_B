package com.bproject.ehm.platform.audit.application;

import com.bproject.audit.client.CanonicalJson;
import com.bproject.audit.client.LogEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Durable execution journal. Expiry means UNKNOWN, never an invented success or automatic rerun. */
@Service @Profile("server")
public class EhmAuditedTasks {
    public static final String REQUEST_TASK = EhmAuditedTasks.class.getName()+".id";
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final EhmAuditRelay relay;
    private final TransactionTemplate independent;
    private final String table, owner=UUID.randomUUID().toString();
    private final boolean autoDdl;
    private final long maximumSeconds;
    private final Set<String> running=ConcurrentHashMap.newKeySet();
    public EhmAuditedTasks(JdbcTemplate db,ObjectMapper mapper,EhmAuditRelay relay,TransactionTemplate tx,
            @Value("${ehm.persistence.schema:ehm}") String schema,
            @Value("${ehm.persistence.auto-ddl:false}") boolean autoDdl,
            @Value("${ehm.audit.task-max-seconds:900}") long maximumSeconds,
            @Value("${ehm.audit.task-table:audit_task}") String taskTable) {
        if(!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||!taskTable.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||maximumSeconds<30||maximumSeconds>86400)
            throw new IllegalArgumentException("Invalid task audit schema/deadline");
        this.db=db;this.mapper=mapper;this.relay=relay;this.table=schema+"."+taskTable;this.autoDdl=autoDdl;this.maximumSeconds=maximumSeconds;
        this.independent=new TransactionTemplate(tx.getTransactionManager());
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @PostConstruct void initialize() {
        if(autoDdl)db.execute("CREATE TABLE IF NOT EXISTS "+table+" (task_id VARCHAR(64) PRIMARY KEY,owner_id VARCHAR(64) NOT NULL,state VARCHAR(32) NOT NULL,base_event TEXT NOT NULL,started_at TIMESTAMP WITH TIME ZONE NOT NULL,finished_at TIMESTAMP WITH TIME ZONE,lease_until TIMESTAMP WITH TIME ZONE NOT NULL,deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,error_code VARCHAR(96))");
        db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE 1=0",Long.class);
    }
    public String start(HttpServletRequest request,String action,String menu) {
        if(!Set.of("EXECUTE","EXPORT","BACKUP","RESTORE","TRAIN").contains(action)||!menu.matches("[a-z][a-z0-9-]{0,80}"))
            throw new IllegalArgumentException("Invalid audited task action/menu");
        String id="TASK-"+UUID.randomUUID();Instant now=Instant.now();
        LogEvent original=relay.event(request,"STARTED","JOB",0);
        LogEvent first=new LogEvent(1,id+"-START",now,original.appCode(),original.domainId(),original.zoneId(),original.userId(),original.actorTrust(),
                menu,action,id,"STARTED",original.method(),original.path(),original.traceId(),0,"JOB","任务开始；对象引用在本地任务记录与请求审计中核对，不记录业务正文");
        independent.executeWithoutResult(status->{
            db.update("INSERT INTO "+table+"(task_id,owner_id,state,base_event,started_at,lease_until,deadline_at) VALUES(?,?,?,?,?,?,?)",
                    id,owner,"RUNNING",CanonicalJson.encode(mapper,first),Timestamp.from(now),Timestamp.from(now.plusSeconds(90)),Timestamp.from(now.plusSeconds(maximumSeconds)));
            relay.enqueue(first);
        });
        running.add(id);request.setAttribute(REQUEST_TASK,id);return id;
    }
    public boolean finish(String id,String outcome) {
        if(!Set.of("SUCCESS","FAILED","DENIED").contains(outcome))throw new IllegalArgumentException("Invalid task outcome");
        try {
            return Boolean.TRUE.equals(independent.execute(status->{
                var rows=db.queryForList("SELECT base_event FROM "+table+" WHERE task_id=? AND owner_id=? AND state='RUNNING'",id,owner);
                if(rows.isEmpty())return false;
                Instant now=Instant.now();
                int changed=db.update("UPDATE "+table+" SET state=?,finished_at=?,error_code=? WHERE task_id=? AND owner_id=? AND state='RUNNING'",
                        outcome.equals("SUCCESS")?"SUCCEEDED":"FAILED",Timestamp.from(now),outcome.equals("SUCCESS")?null:outcome.equals("DENIED")?"ACCESS_DENIED":"EXECUTION_FAILED",id,owner);
                if(changed!=1)return false;
                relay.enqueue(terminal((String)rows.get(0).get("base_event"),id+"-FINISH",now,outcome,
                        outcome.equals("SUCCESS")?"实际执行已完成；导出只证明服务端字节生成，不证明浏览器保存或上链":"实际执行失败或被拒绝；不记录异常正文"));
                return true;
            }));
        } finally {running.remove(id);}
    }
    @Scheduled(fixedDelayString="${ehm.audit.task-check-ms:30000}")
    public void reconcileExpired() {
        Instant now=Instant.now();
        for(String id:running)db.update("UPDATE "+table+" SET lease_until=? WHERE task_id=? AND owner_id=? AND state='RUNNING' AND deadline_at>?",
                Timestamp.from(now.plusSeconds(90)),id,owner,Timestamp.from(now));
        var stale=db.queryForList("SELECT task_id,base_event FROM "+table+" WHERE state='RUNNING' AND (lease_until<=? OR deadline_at<=?) ORDER BY started_at LIMIT 50",Timestamp.from(now),Timestamp.from(now));
        for(var row:stale)independent.executeWithoutResult(status->{
            String id=(String)row.get("task_id");
            int changed=db.update("UPDATE "+table+" SET state='UNKNOWN',finished_at=?,error_code='EXECUTION_RESULT_UNKNOWN' WHERE task_id=? AND state='RUNNING' AND (lease_until<=? OR deadline_at<=?)",
                    Timestamp.from(now),id,Timestamp.from(now),Timestamp.from(now));
            if(changed==1)relay.enqueue(terminal((String)row.get("base_event"),id+"-UNKNOWN",now,"STARTED","执行结果未知，需人工核对；不冒充失败或成功，不自动重做业务"));
        });
    }
    public List<Map<String,Object>> latest() {
        return db.query("SELECT task_id,state,base_event,started_at,finished_at,error_code FROM "+table+" ORDER BY started_at DESC,task_id LIMIT 30",(rs,n)->{
            LogEvent base=decode(rs.getString("base_event"));var row=new LinkedHashMap<String,Object>();
            row.put("taskId",rs.getString("task_id"));row.put("state",rs.getString("state"));row.put("action",base.action());row.put("menuId",base.menuId());
            row.put("userId",base.userId());row.put("actorTrust",base.actorTrust());row.put("startedAt",rs.getTimestamp("started_at").toInstant().toString());
            row.put("finishedAt",rs.getTimestamp("finished_at")==null?null:rs.getTimestamp("finished_at").toInstant().toString());row.put("errorCode",rs.getString("error_code"));return row;
        });
    }
    private LogEvent terminal(String json,String id,Instant now,String outcome,String summary) {
        LogEvent e=decode(json);
        return new LogEvent(1,id,now,e.appCode(),e.domainId(),e.zoneId(),e.userId(),e.actorTrust(),e.menuId(),e.action(),e.objectId(),outcome,e.method(),e.path(),e.traceId(),Math.max(0,java.time.Duration.between(e.occurredAt(),now).toMillis()),"JOB",summary);
    }
    private LogEvent decode(String text){try{return mapper.readValue(text,LogEvent.class);}catch(Exception invalid){throw new IllegalStateException("Invalid immutable task event",invalid);}}
}
