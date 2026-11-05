package com.mt.wms.agent;

import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.auth.sso.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;
import static com.mt.wms.agent.AgentModels.*;

/** A scheduled task retains a reference, never a bearer token or cached authorization. */
@Service
class WmsAgentAuthorization {
    static final String MENU="WMS:warehouse-agent";
    private final JdbcClient jdbc;
    private final WmsSsoExchangeService iam;
    WmsAgentAuthorization(JdbcClient jdbc,WmsSsoExchangeService iam){this.jdbc=jdbc;this.iam=iam;}
    record Reference(int version,String subject,String sessionId) {}
    static Reference capture(){
        var context=WmsCentralScope.current();
        if(context==null)return new Reference(1,null,null);
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs))throw denied();
        Object raw=attrs.getRequest().getAttribute("WMS_IAM_AUTHORIZATION");
        if(!(raw instanceof JsonNode assertion)||assertion.path("sessionId").asText().isBlank()
                ||!context.userId().equals(assertion.path("subject").asText()))throw denied();
        return new Reference(2,assertion.path("subject").asText(),assertion.path("sessionId").asText());
    }
    static void require(UserContext context,String operation){
        if(context==null)return;
        if(context.readOnly()||!context.hasAction(MENU,"VIEW")
                ||!context.hasAction(MENU,operation)||!context.hasAction("WMS:inventory","VIEW")
                ||context.scopeMode()!=ScopeSql.Mode.SCOPED||context.activeScope().size()!=1
                ||!"WMS".equals(context.activeScope().get(0).subsystemCode()))throw denied();
    }
    static void requireSave(boolean create,ScheduleRequest request){
        var context=WmsCentralScope.current();
        require(context,create?"ADD":"EDIT");
        if(request.enabled())require(context,"FAULT_ALARM");
        if(!AgentPatrolService.emails(request.emails()).equals("-"))require(context,"RECEIVE_EMAIL");
    }
    void require(Schedule job,boolean email){
        var row=jdbc.sql("SELECT scope_context_version,iam_subject,iam_session_id FROM wms_agent_schedule WHERE id=:id")
                .param("id",job.id()).query().singleRow();
        int version=((Number)row.get("scope_context_version")).intValue();
        String subject=Objects.toString(row.get("iam_subject"),""),sid=Objects.toString(row.get("iam_session_id"),"");
        var mappings=jdbc.sql("SELECT iam_subject FROM wms_sso_subject_map WHERE local_user_id=:user AND audience='wms'")
                .param("user",job.userId()).query(String.class).list();
        if(version==1){
            // An old centrally managed task cannot silently fall back to local/V1 permissions.
            if(!mappings.isEmpty()||!subject.isBlank()||!sid.isBlank())throw denied();
            return;
        }
        if(version!=2||subject.isBlank()||sid.isBlank()||!mappings.contains(subject))throw denied();
        int enabled=jdbc.sql("SELECT count(*) FROM wms_sso_subject_map WHERE iam_subject=:subject AND audience='wms' AND local_user_id=:user AND enabled=1")
                .param("subject",subject).param("user",job.userId()).query(Integer.class).single();
        if(enabled!=1)throw denied();
        var context=WmsCentralScope.validate(iam.currentAuthorization(subject,sid),new ObjectMapper());
        if(context==null)throw denied();
        require(context,"FAULT_ALARM");
        if(email)require(context,"RECEIVE_EMAIL");
        var filter=ScopeSql.build(context.scopeMode(),context.activeScope(),ScopeSql.Columns.ORDER);
        int warehouses=jdbc.sql("SELECT count(*) FROM wms_warehouse o WHERE o.id=:warehouse AND o.company_id=:company AND o.status='ENABLED'"+filter.my_info_template())
                .param("warehouse",job.warehouseId()).param("company",job.companyId()).params(filter.scope_params())
                .query(Integer.class).single();
        if(warehouses!=1)throw denied();
    }
    private static AccessDeniedException denied(){return new AccessDeniedException("巡检的中央会话、操作授权或仓库归属已失效，请重新授权并保存任务");}
}
