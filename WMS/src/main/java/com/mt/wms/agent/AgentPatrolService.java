package com.mt.wms.agent;

import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.util.*;
import static com.mt.wms.agent.AgentModels.*;

@Service
class AgentPatrolService {
    private static final Logger log=LoggerFactory.getLogger(AgentPatrolService.class);
    private final AgentRepository repository;private final AgentAnalytics analytics;
    private final TransactionTemplate transaction;private final ObjectProvider<JavaMailSender> mail;
    private final String host,from;private final ZoneId zone=ZoneId.of("Asia/Shanghai");
    private final boolean enabled,mailEnabled;
    AgentPatrolService(AgentRepository repository,AgentAnalytics analytics,TransactionTemplate transaction,
            ObjectProvider<JavaMailSender> mail,@Value("${spring.mail.host:}")String host,
            @Value("${wms.agent.mail-from:}")String from,@Value("${wms.agent.enabled:true}")boolean enabled,
            @Value("${wms.agent.mail-enabled:false}")boolean mailEnabled) {
        this.repository=repository;this.analytics=analytics;this.transaction=transaction;this.mail=mail;this.host=host;this.from=from;this.enabled=enabled;this.mailEnabled=mailEnabled;
    }
    boolean mailReady() {return mailEnabled&&!host.isBlank()&&!from.isBlank()&&mail.getIfAvailable()!=null;}
    static String emails(String text) {
        if(text==null||text.isBlank()||"-".equals(text))return "-";
        String[] parts=text.replace('；',';').replace(';',',').split(",");
        if(parts.length>5)throw new IllegalArgumentException("最多指定5个接收邮箱");
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for(String part:parts) {
            String address=part.trim();
            if(address.contains("\r")||address.contains("\n"))throw new IllegalArgumentException("邮箱地址格式错误");
            try {InternetAddress parsed=new InternetAddress(address,true);parsed.validate();
                if(!parsed.getAddress().equals(address)||!address.contains("@"))throw new IllegalArgumentException();
            } catch(Exception e){throw new IllegalArgumentException("接收邮箱格式不正确："+address);}
            result.add(address);
        }
        return String.join(",",result);
    }
    void save(Scope s,Long id,ScheduleRequest request) {
        if(!request.lowStock()&&!request.replenishment()&&!request.frozenStock()&&!request.capacity())throw new IllegalArgumentException("至少启用一种巡检规则");
        OffsetDateTime next=AgentMath.nextRun(request.frequency(),request.intervalMinutes(),request.dailyTime(),Clock.systemUTC(),zone);
        repository.saveSchedule(s,id,request,emails(request.emails()),next);
    }
    Map<String,Object> runNow(Scope s,long id) {
        Schedule job=repository.schedules(s).stream().filter(j->j.id()==id).findFirst().orElseThrow(()->new IllegalArgumentException("巡检任务不存在"));
        return run(job,true);
    }
    @Scheduled(fixedDelayString="${wms.agent.poll-millis:60000}",initialDelay=30000)
    public void poll() {
        if(!enabled)return;
        try {for(Schedule job:repository.due())try{run(job,false);}catch(Exception e){log.warn("WMS patrol job {} failed; retry scheduled",job.id());}}
        catch(Exception e){log.warn("WMS patrol storage unavailable; existing business remains available");}
    }
    Map<String,Object> run(Schedule job,boolean manual) {
        if(!repository.claim(job,manual))throw new IllegalArgumentException("该任务正在运行，请稍后重试");
        try {
            if(!repository.permitted(job))throw new IllegalArgumentException("任务创建人的账号、仓库或货主权限已失效，请重新配置");
            Scope s=new Scope(job.companyId(),job.warehouseId(),job.ownerId(),job.userId(),repository.allOwners(job.userId()));
            return transaction.execute(status->{
                Map<String,Object> utilization=analytics.utilization(s,true);
                List<Finding> findings=analytics.findings(job,s,utilization);
                List<Finding> fresh=new ArrayList<>();List<String> keys=findings.stream().map(Finding::key).toList();
                var jdbc=repository.client();
                for(Finding finding:findings) {
                    var old=jdbc.sql("SELECT id,state,level,last_notified_at FROM wms_agent_alert WHERE schedule_id=:job AND object_key=:key")
                        .param("job",job.id()).param("key",finding.key()).query().listOfRows().stream().findFirst();
                    boolean notify=old.isEmpty();
                    if(old.isPresent()) {
                        var row=old.get();
                        OffsetDateTime last=jdbc.sql("SELECT last_notified_at FROM wms_agent_alert WHERE id=:id").param("id",row.get("id")).query(OffsetDateTime.class).single();
                        notify="RESOLVED".equals(row.get("state"))||Duration.between(last,OffsetDateTime.now()).toHours()>=job.cooldownHours()
                            ||("CRITICAL".equals(finding.level())&&!"CRITICAL".equals(row.get("level")));
                        jdbc.sql("UPDATE wms_agent_alert SET title=:title,body=:body,level=:level,state='OPEN',updated_at=CURRENT_TIMESTAMP,last_notified_at=CASE WHEN :notify THEN CURRENT_TIMESTAMP ELSE last_notified_at END,read_at=CASE WHEN :notify THEN NULL ELSE read_at END WHERE id=:id")
                            .param("title",finding.title()).param("body",finding.body()).param("level",finding.level()).param("notify",notify).param("id",row.get("id")).update();
                    } else {
                        jdbc.sql("INSERT INTO wms_agent_alert(schedule_id,company_id,warehouse_id,owner_id,user_id,object_key,kind,title,body,level) VALUES(:job,:c,:w,:o,:u,:key,:kind,:title,:body,:level)")
                            .param("job",job.id()).param("c",s.companyId()).param("w",s.warehouseId()).param("o",s.ownerId()).param("u",s.userId()).param("key",finding.key()).param("kind",finding.kind()).param("title",finding.title()).param("body",finding.body()).param("level",finding.level()).update();
                    }
                    if(notify)fresh.add(finding);
                }
                String resolve="UPDATE wms_agent_alert SET state='RESOLVED',updated_at=CURRENT_TIMESTAMP WHERE schedule_id=:job AND state='OPEN'"+(keys.isEmpty()?"":" AND object_key NOT IN (:keys)");
                var query=jdbc.sql(resolve).param("job",job.id());if(!keys.isEmpty())query=query.param("keys",keys);query.update();
                if(!fresh.isEmpty()&&!"-".equals(job.emails())) {
                    String body="WMS 库存巡检："+job.name()+"\n检查时间："+ZonedDateTime.now(zone)+"\n\n"+
                        String.join("\n\n",fresh.stream().map(f->"["+f.level()+"] "+f.title()+"\n"+f.body()).toList())+"\n\n请登录 WMS 的仓储智能体 > 站内预警查看。告警由规则计算，没有自动修改库存。";
                    jdbc.sql("INSERT INTO wms_agent_mail(schedule_id,user_id,recipients,subject,body,state) VALUES(:job,:u,:to,:subject,:body,:state)")
                        .param("job",job.id()).param("u",job.userId()).param("to",job.emails()).param("subject","WMS预警 · "+job.name())
                        .param("body",body).param("state",mailReady()?"PENDING":"UNCONFIGURED").update();
                }
                repository.finishSchedule(job,AgentMath.nextRun(job.frequency(),job.intervalMinutes(),job.dailyTime(),Clock.systemUTC(),zone),null);
                return Map.<String,Object>of("findings",findings.size(),"newNotifications",fresh.size(),"emailState",mailReady()?"QUEUED":"UNCONFIGURED");
            });
        } catch(Exception e) {
            String error=e instanceof IllegalArgumentException?e.getMessage():"巡检数据暂时不可用，将在5分钟后重试";
            repository.finishSchedule(job,OffsetDateTime.now().plusMinutes(5),error);
            throw new IllegalArgumentException(error);
        }
    }
    List<Map<String,Object>> mails(Scope s) {
        return repository.scoped("SELECT m.id,m.recipients,m.subject,m.state,m.attempts,m.last_error,m.sent_at,m.created_at FROM wms_agent_mail m JOIN wms_agent_schedule j ON j.id=m.schedule_id WHERE j.company_id=:c AND j.warehouse_id=:w AND j.owner_id=:o AND m.user_id=:u ORDER BY m.id DESC LIMIT 50",s).param("u",s.userId()).query().listOfRows();
    }
    void retryMail(Scope s,long id) {
        if(!mailReady())throw new IllegalArgumentException("邮件服务器尚未配置");
        int count=repository.scoped("UPDATE wms_agent_mail SET state='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=:id AND user_id=:u AND state IN ('FAILED','UNCONFIGURED','RETRY') AND schedule_id IN (SELECT id FROM wms_agent_schedule WHERE company_id=:c AND warehouse_id=:w AND owner_id=:o AND user_id=:u)",s).param("id",id).param("u",s.userId()).update();
        if(count!=1)throw new IllegalArgumentException("邮件不存在、正在发送或已经发送");
    }
    @Scheduled(fixedDelay=30000,initialDelay=40000)
    public void sendMail() {
        if(!enabled||!mailReady())return;
        var jdbc=repository.client();
        try {
            // 重启或中断后的租约恢复。SMTP为至少一次投递，极少数超时场景可能重复邮件。
            jdbc.sql("UPDATE wms_agent_mail SET state='RETRY' WHERE state='SENDING' AND next_attempt_at<CURRENT_TIMESTAMP").update();
            List<Map<String,Object>> rows=jdbc.sql("SELECT * FROM wms_agent_mail WHERE state IN ('PENDING','RETRY','UNCONFIGURED') AND attempts<5 AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 5").query().listOfRows();
            for(var row:rows) {
                long id=((Number)row.get("id")).longValue();int attempt=((Number)row.get("attempts")).intValue()+1;
                if(jdbc.sql("UPDATE wms_agent_mail SET state='SENDING',attempts=attempts+1,next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '10 minutes' WHERE id=:id AND state IN ('PENDING','RETRY','UNCONFIGURED')").param("id",id).update()!=1)continue;
                try {
                    Schedule schedule=jdbc.sql("SELECT * FROM wms_agent_schedule WHERE id=:id").param("id",row.get("schedule_id")).query(Schedule.class).single();
                    if(!repository.permitted(schedule))throw new IllegalArgumentException("接收人的任务权限已失效");
                    SimpleMailMessage message=new SimpleMailMessage();message.setFrom(from);message.setTo(emails(row.get("recipients").toString()).split(","));
                    message.setSubject(row.get("subject").toString());message.setText(row.get("body").toString());
                    Objects.requireNonNull(mail.getIfAvailable()).send(message);
                    jdbc.sql("UPDATE wms_agent_mail SET state='SENT',sent_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=:id").param("id",id).update();
                } catch(Exception e) {
                    jdbc.sql("UPDATE wms_agent_mail SET state=:state,last_error=:error,next_attempt_at=:next WHERE id=:id")
                        .param("state",attempt>=5?"FAILED":"RETRY").param("error",e instanceof IllegalArgumentException?e.getMessage():"SMTP发送失败，请检查邮箱授权、网络和配置；已安排重试")
                        .param("next",OffsetDateTime.now().plusMinutes(Math.min(30,1L<<attempt))).param("id",id).update();
                }
            }
        } catch(Exception e){log.warn("WMS mail queue unavailable; station alerts remain available");}
    }
}
