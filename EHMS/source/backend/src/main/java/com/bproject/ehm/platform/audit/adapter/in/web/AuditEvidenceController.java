package com.bproject.ehm.platform.audit.adapter.in.web;

import com.bproject.audit.client.CanonicalJson;
import com.bproject.ehm.platform.audit.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Bounded local evidence export, not a database backup or independently verified chain proof. */
@RestController @Profile("server") @RequestMapping("/api/ehm/v1/system")
public class AuditEvidenceController {
    private final EhmAuditedTasks tasks;private final EhmAuditRelay relay;private final JdbcTemplate db;private final ObjectMapper mapper;
    private final boolean enabled;private final Set<String> actors;private final String version;
    public AuditEvidenceController(EhmAuditedTasks tasks,EhmAuditRelay relay,JdbcTemplate db,ObjectMapper mapper,
            @Value("${ehm.audit.evidence-export-enabled:false}") boolean enabled,
            @Value("${ehm.audit.evidence-export-actors:}") String actors,
            @Value("${ehm.release.version:unknown}") String version) {
        this.tasks=tasks;this.relay=relay;this.db=db;this.mapper=mapper;this.enabled=enabled;this.version=version;
        this.actors=new HashSet<>();for(String id:actors.split(","))if(!id.isBlank())this.actors.add(id.trim());
    }
    private boolean allowed(HttpServletRequest request) {
        var actor=IamAuditIdentityFilter.actor(request);return enabled&&actor!=null&&!IamAuditIdentityFilter.readOnly(request)&&(actors.contains(actor.getName())||com.bproject.ehm.platform.security.BusinessPermissionFilter.admin(request));
    }
    @GetMapping("/audit-tasks") public Map<String,Object> list(HttpServletRequest request){
        var actor=IamAuditIdentityFilter.actor(request);boolean all=allowed(request);
        var visible=actor==null?List.of():tasks.latest().stream().filter(row->all||actor.getName().equals(row.get("userId"))).toList();
        return Map.of("tasks",visible,"identityVerified",actor!=null,"exportAllowed",all,"exportEnabled",enabled,"scope","LOCAL_EXECUTION_JOURNAL_NOT_CHAIN_CONFIRMATION");
    }
    @GetMapping("/audit-evidence/export") @AuditedTask(action="EXPORT",menu="audit-package")
    public ResponseEntity<byte[]> export(HttpServletRequest request) throws Exception {
        if(!allowed(request))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"审计证据导出需启用配置，并由已验证IAM身份获得本地专项授权；角色预览不授予权限");
        String database=db.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection->connection.getMetaData().getDatabaseProductName());
        var evidence=new LinkedHashMap<String,Object>();evidence.put("formatVersion",1);evidence.put("generatedAt",Instant.now().toString());evidence.put("edition",version);
        evidence.put("scope","LOCAL_EHM_AUDIT_EVIDENCE_NOT_FULL_BACKUP");evidence.put("databaseProduct",database);
        evidence.put("exportTaskId",request.getAttribute(EhmAuditedTasks.REQUEST_TASK));evidence.put("delivery",relay.status());evidence.put("tasksAtCaptureTime",tasks.latest());
        evidence.put("limitations",List.of("仅含本地队列汇总、最近20条事件和最近30个任务，不是全量备份","当前导出任务在采样时可能仍显示RUNNING，终态另查任务记录","公共队列ACCEPTED仅证明AUDIT入库，不证明TRUST已最终上链","服务端生成字节不证明浏览器已保存，未从浏览器补报成功","不含请求正文、数据库配置、原始凭据或AI对话"));
        String json=mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence);byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>1048576)throw new IllegalStateException("Bounded evidence size exceeded");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .header("Content-Disposition","attachment; filename=ehm-audit-evidence.json")
                .header("X-EHM-Evidence-Sha256",CanonicalJson.hash(json)).body(bytes);
    }
}
