package com.bdemo.sys.log;
import com.bproject.audit.client.JdbcLogOutbox;
import com.bproject.audit.client.LegacyLogMapper;
import com.bproject.audit.client.LogDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Maintainer-reviewed integration: scans immutable SYS rows into its own durable outbox. */
@Component @ConditionalOnProperty(name="sys.audit.public.enabled",havingValue="true")
public class PublicAuditExport {
    private final JdbcTemplate db; private final JdbcLogOutbox queue; private final LogDispatcher dispatch;
    private final String domain,zone,since;
    public PublicAuditExport(JdbcTemplate db,ObjectMapper mapper,
            @Value("${sys.audit.public.endpoint}") String endpoint,@Value("${sys.audit.public.token-file}") String file,
            @Value("${sys.audit.public.domain}") String domain,@Value("${sys.audit.public.zone}") String zone,
            @Value("${sys.audit.public.since:}") String since) throws Exception {
        this.db=db;this.domain=domain;this.zone=zone;this.since=since==null?"":since.trim();this.queue=new JdbcLogOutbox(db,mapper,"sys.sys_audit_outbox");
        this.dispatch=new LogDispatcher(queue,mapper,endpoint,Files.readString(Path.of(file)).trim());
    }
    @Scheduled(fixedDelayString="${sys.audit.public.interval-ms:5000}") public void export(){
        // Event ID prefix is immutable; a restart rereads only records not yet queued.
        String where="NOT EXISTS (SELECT 1 FROM sys.sys_audit_outbox o WHERE o.event_id=('sys-' || l.id))";
        List<Object> args=new ArrayList<>();
        if(!since.isEmpty()){where+=" AND l.created_at >= ?";args.add(LocalDateTime.parse(since));}
        for(var row:db.queryForList("SELECT l.* FROM sys_operation_log l WHERE "+where+" ORDER BY l.created_at,l.id LIMIT 50",args.toArray()))
            queue.enqueue(LegacyLogMapper.map(row,"sys",domain,zone,ZoneId.of("Asia/Shanghai")));
        dispatch.dispatch();
    }
}
