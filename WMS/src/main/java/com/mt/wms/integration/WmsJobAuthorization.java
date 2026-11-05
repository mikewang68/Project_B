package com.mt.wms.integration;

import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.auth.*;
import com.mt.wms.auth.sso.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;

/** Persist only a session reference, then recheck central and native rights in the worker. */
@Service
class WmsJobAuthorization {
    private final JdbcClient jdbc;private final WmsSsoExchangeService iam;private final AuthRepository auth;
    WmsJobAuthorization(JdbcClient jdbc,WmsSsoExchangeService iam,AuthRepository auth){this.jdbc=jdbc;this.iam=iam;this.auth=auth;}
    record Reference(int version,String subject,String sessionId) {}
    static Reference capture(){
        if(WmsCentralScope.current()==null)return new Reference(1,null,null);
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))throw denied();
        Object raw=attributes.getRequest().getAttribute("WMS_IAM_AUTHORIZATION");
        if(!(raw instanceof JsonNode assertion)||assertion.path("subject").asText().isBlank()
                ||assertion.path("sessionId").asText().isBlank())throw denied();
        return new Reference(2,assertion.path("subject").asText(),assertion.path("sessionId").asText());
    }
    static String menu(String resource){return switch(resource){
        case "GOODS","PARTNERS" -> "WMS:master-data";
        case "STOCKIN" -> "WMS:stockin";case "STOCKOUT" -> "WMS:stockout";
        case "INVENTORY" -> "WMS:inventory";case "FINANCE" -> "WMS:finance";
        default -> throw denied();
    };}
    static void require(UserContext context,String resource,String operation){
        if(context==null)return;
        if(!context.hasAction("WMS:integration",operation)||!context.hasAction(menu(resource),"VIEW"))throw denied();
        if(operation.equals("UPLOAD")){
            if(context.readOnly()||!context.hasAction(menu(resource),"ADD")
                    ||context.scopeMode()!=ScopeSql.Mode.SCOPED||context.activeScope().size()!=1)throw denied();
            if(Set.of("GOODS","PARTNERS").contains(resource)&&!context.adminLevel().equals("L1_SUPER"))throw denied();
        }
    }
    void require(IntegrationModels.TaskRow task){
        var metadata=jdbc.sql("SELECT scope_context_version,iam_subject,iam_session_id FROM async_task WHERE id=:id")
                .param("id",task.id()).query().singleRow();
        if(((Number)metadata.get("scope_context_version")).intValue()!=2)return;
        String subject=Objects.toString(metadata.get("iam_subject"),""),sid=Objects.toString(metadata.get("iam_session_id"),"");
        if(subject.isBlank()||sid.isBlank())throw denied();
        var assertion=iam.currentAuthorization(subject,sid);
        var context=WmsCentralScope.validate(assertion,new ObjectMapper());
        if(context==null)throw denied();
        String operation=task.taskType().equals("EXPORT")?"EXPORT":"UPLOAD";
        require(context,task.resourceType(),operation);
        var minimal=new WmsPrincipal(task.createdBy(),task.companyId(),"","","","",List.of(),Set.of(),false);
        var nativeUser=auth.refreshPrincipal(minimal).orElseThrow(WmsJobAuthorization::denied);
        if(nativeUser.readOnly()&&operation.equals("UPLOAD"))throw denied();
        String nativeResource=menu(task.resourceType()).substring(4);
        if(nativeResource.equals("master-data"))nativeResource="master";
        if(!nativeUser.permissions().contains("integration:manage")
                ||!nativeUser.permissions().contains(nativeResource+":"+(operation.equals("UPLOAD")?"write":"read")))throw denied();
        boolean all=nativeUser.permissions().contains("tenant:all");
        if(auth.findWarehouses(nativeUser.userId(),task.companyId(),all).stream().noneMatch(w->w.id().equals(task.warehouseId()))
                ||auth.findOwners(nativeUser.userId(),task.companyId(),all).stream().noneMatch(o->o.id().equals(task.ownerId())))throw denied();
        var filter=ScopeSql.build(context.scopeMode(),context.activeScope(),ScopeSql.Columns.ORDER);
        var statement=jdbc.sql("SELECT count(*) FROM wms_warehouse o WHERE o.id=:id AND o.company_id=:company AND o.status='ENABLED'"+filter.my_info_template())
                .param("id",task.warehouseId()).param("company",task.companyId()).params(filter.scope_params());
        if(statement.query(Integer.class).single()!=1)throw denied();
    }
    private static AccessDeniedException denied(){return new AccessDeniedException("任务当前身份、资源权限或数据范围已失效");}
}
