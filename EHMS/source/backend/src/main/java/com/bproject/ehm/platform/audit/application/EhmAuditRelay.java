package com.bproject.ehm.platform.audit.application;

import com.bproject.audit.client.JdbcLogOutbox;
import com.bproject.audit.client.LogDispatcher;
import com.bproject.audit.client.LogEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service @Profile("server")
public class EhmAuditRelay {
    public static final String EVENT_ID = EhmAuditRelay.class.getName() + ".event";
    public static final String BUSINESS_RECORDED = EhmAuditRelay.class.getName() + ".committed";
    private final JdbcLogOutbox outbox;
    private final JdbcTemplate db;
    private final String schema;
    private final String queueTable;
    private final boolean autoDdl;
    private final String domain, zone;
    private final LogDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final java.util.concurrent.atomic.AtomicLong enqueueFailures = new java.util.concurrent.atomic.AtomicLong();
    private volatile Instant lastEnqueueFailure;
    public EhmAuditRelay(JdbcTemplate db, ObjectMapper mapper,
                        @Value("${ehm.persistence.schema:ehm}") String schema,
                        @Value("${ehm.persistence.auto-ddl:false}") boolean autoDdl,
                        @Value("${ehm.audit.domain:unassigned}") String domain,
                        @Value("${ehm.audit.zone:unassigned}") String zone,
                        @Value("${ehm.audit.endpoint:}") String endpoint,
                        @Value("${ehm.audit.token-file:}") String tokenFile,
                        @Value("${ehm.audit.outbox-table:audit_outbox}") String queueTable) throws Exception {
        this.db = db; this.schema = schema; this.autoDdl = autoDdl; this.domain = domain; this.zone = zone; this.mapper = mapper;
        this.queueTable=schema+"."+queueTable;
        this.outbox = new JdbcLogOutbox(db, mapper, this.queueTable);
        this.dispatcher = endpoint.isBlank() ? null : new LogDispatcher(outbox, mapper, endpoint, Files.readString(Path.of(tokenFile)).trim());
        if (dispatcher != null && (domain.equals("unassigned") || zone.equals("unassigned"))) throw new IllegalArgumentException("Configure audit domain/zone before remote delivery");
    }
    @PostConstruct void initialize() {
        if (autoDdl) { db.execute("CREATE SCHEMA IF NOT EXISTS " + schema); outbox.initialize(); }
        // Fail startup rather than silently running without the durable queue migration.
        outbox.statistics();
    }
    @Scheduled(fixedDelayString="${ehm.audit.interval-ms:5000}")
    public void dispatch() { if (dispatcher != null) dispatcher.dispatch(); }
    public void record(HttpServletRequest request, String outcome, String source, long duration) {
        enqueue(event(request, outcome, source, duration));
    }
    void enqueue(LogEvent event) {
        try { outbox.enqueue(event); }
        catch (RuntimeException failure) { enqueueFailures.incrementAndGet(); lastEnqueueFailure = Instant.now(); throw failure; }
    }
    public LogEvent event(HttpServletRequest request, String outcome, String source, long duration) {
        String id = (String) request.getAttribute(EVENT_ID);
        if (id == null) { id = "EHM-" + UUID.randomUUID(); request.setAttribute(EVENT_ID,id); }
        String uri = request.getRequestURI(), path = AuditRequestMetadata.route(request);
        var actor = com.bproject.ehm.platform.audit.adapter.in.web.IamAuditIdentityFilter.actor(request);
        String user = actor == null ? "anonymous" : safe(actor.getName(),128);
        String object = AuditRequestMetadata.objectId(request);
        return new LogEvent(1,id,Instant.now(),"ehealth",domain,zone,user,actor==null?"ANONYMOUS":"AUTHENTICATED",
                menu(uri),action(request.getMethod(),uri),object,outcome,request.getMethod(),path,
                safe((String)request.getAttribute("auditTraceId"),128),Math.max(0,duration),source,
                source.equals("REQUEST") ? "HTTP请求结果，不等同于异步任务、导出或AI推理完成；不含正文及查询条件" : "业务事务操作元数据，不含请求正文或查询条件");
    }
    public void clientEvent(HttpServletRequest request, String menu, String action) {
        if (!java.util.Set.of("NAVIGATE","EXPORT").contains(action) || menu == null || !menu.matches("[a-z][a-z0-9-]{0,80}")) throw new IllegalArgumentException("Invalid client log event");
        var actor = com.bproject.ehm.platform.audit.adapter.in.web.IamAuditIdentityFilter.actor(request);
        enqueue(new LogEvent(1,"EHM-"+UUID.randomUUID(),Instant.now(),"ehealth",domain,zone,actor==null?"anonymous":safe(actor.getName(),128),actor==null?"ANONYMOUS":"AUTHENTICATED",menu,action,"","STARTED","POST","/api/ehm/v1/system/client-events","",0,"CLIENT","前端浏览或导出请求，不作为业务完成证明"));
    }
    public Map<String,Object> status() {
        return Map.of("durableQueue",true,"deliveryConfigured",dispatcher!=null,"identityMode","verified-principal-or-anonymous",
                "states",outbox.statistics(),"recentEvents",recentEvents(),"enqueueFailuresSinceStartup",enqueueFailures.get(),
                "lastEnqueueFailure",lastEnqueueFailure == null ? "" : lastEnqueueFailure.toString(),
                "chainState","查看公共日志服务回执，队列ACCEPTED只表示统一日志已入库");
    }
    private java.util.List<Map<String,Object>> recentEvents() {
        return db.queryForList("SELECT payload,state,attempts,last_error FROM " + queueTable + " ORDER BY created_at DESC,id DESC LIMIT 20")
                .stream().map(row -> {
                    try {
                        var event = mapper.readTree((String) row.get("payload"));
                        Map<String,Object> view = new java.util.LinkedHashMap<>();
                        for (String field : new String[]{"eventId","occurredAt","userId","menuId","action","objectId","outcome","sourceKind","traceId"})
                            view.put(field,event.path(field).asText());
                        view.put("state",row.get("state")); view.put("attempts",row.get("attempts"));
                        view.put("errorCode",row.get("last_error") == null ? "" : row.get("last_error"));
                        return view;
                    } catch (java.io.IOException failure) { throw new IllegalStateException("Invalid stored audit event",failure); }
                }).toList();
    }
    static String action(String method,String path) {
        return AuditRequestMetadata.action(method,path);
    }
    static String menu(String uri) {
        if(uri.contains("/auth/"))return "iam";
        if(uri.contains("/reliability/alarm-rules"))return "alarm-rules";
        if(uri.contains("/reliability/knowledge-cases"))return "knowledge";
        if(uri.contains("/reliability/fault-codes"))return "fault-codes";
        if(uri.contains("/reliability/sla-policies"))return "sla";
        if(uri.contains("health-assessments"))return "health";
        if(uri.contains("measurement-points")||uri.contains("/telemetry/"))return "measurement";
        if(uri.contains("/components"))return "bom";
        if(uri.contains("/device-templates"))return "device-template";
        if(uri.contains("/calibration-records"))return "sensor-cal";
        if(uri.contains("/configuration-changes"))return "config-change";
        if(uri.contains("/shift-handovers"))return "shift-handover";
        if(uri.contains("/my-tasks"))return "my-tasks";
        if(uri.contains("/work-orders"))return "workorders";
        if(uri.contains("/alarms"))return "alarm-center";
        if(uri.contains("/data-quality"))return "data-quality";
        if(uri.contains("/assistant"))return "ai-assistant";
        if(uri.contains("/maintenance"))return "maintenance-plan";
        if(uri.contains("/inspection"))return "inspection";
        if(uri.contains("/spare"))return "spares";
        if(uri.contains("/reliability"))return "fmeca";
        if(uri.contains("/system/audit"))return "audit-logs";
        if(uri.contains("/system"))return "service-ops";
        if(uri.contains("/dashboard"))return "dashboard";
        if(uri.contains("/analytics"))return "kpi";
        if(uri.contains("/devices")||uri.contains("/assets"))return "asset-tree";
        return "dashboard";
    }
    private static String safe(String value,int size) {
        if(value==null)return ""; return value.replaceAll("[\\p{Cntrl}]", "").substring(0,Math.min(size,value.replaceAll("[\\p{Cntrl}]", "").length()));
    }
}
