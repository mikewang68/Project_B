package com.bproject.safety.config;

import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.context.request.*;

/** Service audience and business tuple are independently validated. */
public final class ScsCentralScope {
  private ScsCentralScope(){}
  private static final String ATTRIBUTE=ScsCentralScope.class.getName();
  public enum Root {
    RULE("safety_rule","rule_code"),FENCE("safety_fence","fence_code"),
    ALERT("safety_alert","alert_no"),PERSONNEL("safety_personnel","personnel_code"),
    COLLISION("collision_device","device_code"),AI_EVENT("ai_event","event_no"),
    AI_MODEL("ai_model","model_code"),EDGE_NODE("edge_node","node_code"),
    EDGE_EVENT("edge_pending_event","idempotency_key"),OPS_LOG("ops_event_log","id"),
    CAMERA("device_camera","camera_code"),AREA("safety_area","area_code"),RUNTIME("scope_runtime_ownership","resource_key");
    final String table,key;Root(String table,String key){this.table=table;this.key=key;}
  }
  public static UserContext validate(Object raw,String subject,ObjectMapper mapper){
    if(raw==null)return null;
    try{return ContextRules.validate(mapper.convertValue(raw,UserContext.class),subject,"scs");}
    catch(Exception error){throw new ScsIamTicketValidator.InvalidTicketException("中央范围无效");}
  }
  public static void bind(HttpServletRequest request,UserContext context){
    if(context==null)request.removeAttribute(ATTRIBUTE);else request.setAttribute(ATTRIBUTE,context);
  }
  public static UserContext current(){
    var machine=ScsMachineRegistry.current();if(machine!=null)return machine.context();
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
      ?(UserContext)a.getRequest().getAttribute(ATTRIBUTE):null;
  }
  /** Set only by the successful machine-key authentication branch, never a client header. */
  public static boolean legacyMachineIngress(){
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
      &&Boolean.TRUE.equals(a.getRequest().getAttribute("SCS_LEGACY_MACHINE_INGRESS"));
  }
  public static boolean permits(UserContext context,String method,String path){
    if(context==null)return true;
    if(method.equals("GET")&&path.equals("/ws/live"))return com.bproject.safety.common.realtime.ScopedLiveEventPolicy.mayConnect(context);
    if(path.matches("/api/v1/shared-dispatch/(plans|work-orders)(/[-_a-zA-Z0-9]{1,128})?"))
      return Set.of("GET","HEAD").contains(method)&&context.hasAction("SCS:rules","VIEW");
    if(path.equals("/api/v1/auth/me")&&Set.of("GET","HEAD").contains(method)
      ||path.equals("/api/v1/auth/sso/logout")&&method.equals("POST"))return true;
    String menu=path.matches("/api/v1/rules(?:/.*)?")?"SCS:rules":path.matches("/api/v1/fences(?:/.*)?")?"SCS:fences":null;
    if(menu==null)return extendedPermits(context,method,path);
    String action;
    if(Set.of("GET","HEAD").contains(method)&&path.matches("/api/v1/(rules|fences)(?:/[^/]+(?:/versions)?)?"))action="VIEW";
    else if(method.equals("POST")&&path.matches("/api/v1/(rules|fences)/[^/]+/(approve|reject|publish|redeliver|rollback)$"))action="CONFIRM";
    else if(method.equals("POST")&&(path.equals("/api/v1/rules")||path.equals("/api/v1/fences")))action="ADD";
    else if(method.equals("PUT")&&path.matches("/api/v1/(rules|fences)/[^/]+")
        ||method.equals("POST")&&path.matches("/api/v1/(rules|fences)/[^/]+/(submit|submit-review|disable)"))action="EDIT";
    else return false;
    return context.hasAction(menu,action)&&(!context.readOnly()||Set.of("VIEW","DOWNLOAD","EXPORT","AUDIT_VERIFY").contains(action));
  }
  /** Read-only POST with no persistence or external model call; CSRF still applies. */
  public static boolean readOnlyQuery(String method,String path){
    return method.equals("POST")&&path.equals("/api/v1/ai/models/infer");
  }
  private static boolean action(UserContext c,String operation,String... menus){
    if(c.readOnly()&&!Set.of("VIEW","DOWNLOAD","EXPORT","AUDIT_VERIFY").contains(operation))return false;
    return Arrays.stream(menus).anyMatch(menu->c.hasAction(menu,operation));
  }
  private static boolean extendedPermits(UserContext c,String method,String path){
    boolean read=Set.of("GET","HEAD").contains(method),post=method.equals("POST");
    if(!path.startsWith("/api/v1/"))return false;
    String p=path.substring(8);
    if(read&&p.matches("overview/(summary|map|alerts/feed|risk-trend|risk-distribution|alerts/[^/]+)"))return action(c,"VIEW","SCS:overview");
    if(read&&p.matches("screen/(overview|alerts/feed|critical)"))return action(c,"VIEW","SCS:safety-screen");
    if(read&&p.equals("mobile/home"))return action(c,"VIEW","SCS:mobile-home");
    if(post&&p.matches("mobile/incidents/[^/]+/(accept|arrive)"))return action(c,"CONFIRM","SCS:mobile-alerts");
    if(read&&p.matches("alerts(?:/[^/]+)?"))return action(c,"VIEW","SCS:alarms","SCS:mobile-alerts");
    if(post&&p.matches("alerts/[^/]+/(confirm|review|review-reject|linkage|takeover)"))return action(c,"CONFIRM","SCS:alarms","SCS:mobile-alerts");
    if(post&&p.matches("alerts/[^/]+/(assign|start|treatment|transfer)"))return action(c,"EDIT","SCS:alarms","SCS:mobile-alerts");
    if(post&&p.matches("alerts/[^/]+/escalate"))return action(c,"FAULT_ALARM","SCS:alarms","SCS:mobile-alerts");
    if(read&&p.matches("personnel(?:/[^/]+(?:/(track|alerts))?)?"))return action(c,"VIEW","SCS:people");
    if(post&&p.matches("personnel/[^/]+/remind"))return action(c,"CONFIRM","SCS:people");
    if(method.equals("PUT")&&p.equals("personnel/config"))return c.adminLevel().equals("L1_SUPER")&&action(c,"EDIT","SCS:people");
    if(read&&p.matches("collision/(devices(?:/[^/]+(?:/distance-trend)?)?|pair)"))return action(c,"VIEW","SCS:devices");
    if(post&&p.matches("collision/(linkage|takeover|release-request)"))return action(c,"CONFIRM","SCS:devices");
    if(read&&(p.matches("ai-events(?:/[^/]+)?")||p.matches("ai-events/snapshots/[^/]+")||p.equals("cameras")))return action(c,"VIEW","SCS:ai");
    if(post&&p.matches("ai-events/[^/]+/(confirm|close)"))return action(c,"CONFIRM","SCS:ai");
    if(post&&p.matches("ai-events/[^/]+/(false-positive|uncertain|assign|process)"))return action(c,"EDIT","SCS:ai");
    if(read&&p.matches("ai/models(?:/[^/]+)?")||readOnlyQuery(method,path))return action(c,"VIEW","SCS:ai-models");
    if(post&&p.equals("ai/models/escalate"))return action(c,"FAULT_ALARM","SCS:ai-models");
    if(post&&p.matches("ai/models/[^/]+/select"))return c.adminLevel().equals("L1_SUPER")&&action(c,"CONFIRM","SCS:ai-models");
    if(method.equals("PUT")&&p.matches("ai/models/[^/]+/config"))return c.adminLevel().equals("L1_SUPER")&&action(c,"EDIT","SCS:ai-models");
    if(read&&p.matches("ops/(overview|topology|edge-nodes(?:/[^/]+)?|devices|interfaces|events)"))return action(c,"VIEW","SCS:operations");
    if(read&&p.matches("edge/(link|local-events|reconcile/rules)"))return action(c,"VIEW","SCS:operations");
    if(post&&(p.matches("ops/edge-nodes/[^/]+/maintain")||p.matches("ops/devices/[^/]+/reconnect")||p.matches("edge/(recover|reconcile/rules|reconcile/time)")))return action(c,"CONFIRM","SCS:operations");
    if(read&&p.matches("analytics/(dataset|events|devices/[^/]+|persons/[^/]+)"))return action(c,"VIEW","SCS:analytics");
    if(read&&p.equals("zones"))return action(c,"VIEW","SCS:overview","SCS:people","SCS:safety-screen");
    if(read&&(p.equals("state")||p.equals("meta/dictionaries")))return action(c,"VIEW","SCS:overview","SCS:alarms","SCS:people","SCS:devices","SCS:ai","SCS:ai-models","SCS:rules","SCS:operations","SCS:analytics","SCS:fences","SCS:mobile-home","SCS:mobile-alerts","SCS:safety-screen");
    // Ingest, background generators and broadcasts have no trusted request tuple.
    return false;
  }
  public static String predicate(Root root,String outer){
    var context=current();if(context==null)return "";
    if(!Set.of(root.table,"f").contains(outer))throw new IllegalArgumentException("Unregistered SQL alias");
    var filter=ScopeSql.build(context.scopeMode(),context.activeScope(),ScopeSql.Columns.ORDER);
    // Only fixed server-owned identifiers and SDK-validated tuple values enter SQL.
    return " AND EXISTS (SELECT 1 FROM safety."+root.table+" o WHERE o.id="+outer+".id"+filter.my_info()+") ";
  }
  public record Stamp(Root root,String key,ScopeTriple scope,boolean created){}
  public static Stamp beforeWrite(NamedParameterJdbcTemplate db,Root root,String key){
    return beforeWrite(db,root,key,root,key);
  }
  /**
   * Machine ingest of a new root row authorizes by a bound object (e.g. CAMERA for AI
   * events) instead of the server-generated business key, which no registration can
   * know in advance. Non-machine callers are unaffected.
   */
  public static Stamp beforeWrite(NamedParameterJdbcTemplate db,Root root,String key,Root machineAuthRoot,String machineAuthKey){
    var machine=ScsMachineRegistry.current();if(machine!=null&&!machine.owns(machineAuthRoot,machineAuthKey))throw ScsMachineRegistry.denied();
    var context=current();
    if(context==null){
      if(legacyMachineIngress()){
        var rows=db.getJdbcTemplate().queryForList("SELECT project_id,region_id,subsystem_code FROM safety."+root.table+" WHERE "+root.key+"=? FOR UPDATE",key);
        if(rows.stream().anyMatch(row->row.get("project_id")!=null||row.get("region_id")!=null||row.get("subsystem_code")!=null))
          throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"机器来源尚未绑定该对象范围");
      }
      return null;
    }
    if(root==Root.AI_MODEL){requireGlobalConfigurationWriter();return null;}
    if(context.readOnly()||context.scopeMode()!=ScopeSql.Mode.SCOPED||context.activeScope().size()!=1)
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"写入须选择唯一授权范围");
    // Held until the repository transaction commits; prevents two domains from
    // concurrently taking over the same globally unique business key.
    if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Scoped write requires a transaction");
    // Existing legacy rule/fence writers still use their original table guard.
    // Extended roots also constrain UPDATE atomically and only lock the exact
    // conflicting row, avoiding a table lock on the live AI-event stream.
    if(root==Root.RULE||root==Root.FENCE)
      db.getJdbcTemplate().execute("LOCK TABLE safety."+root.table+" IN SHARE ROW EXCLUSIVE MODE");
    // Deliberately inspect the raw identity row: a hidden conflicting key must be
    // rejected, never treated as a fresh object by the scoped SELECT adapter.
    var rows=db.getJdbcTemplate().queryForList("SELECT project_id,region_id,subsystem_code FROM safety."+root.table+" WHERE "+root.key+"=? FOR UPDATE",key);
    var scope=context.activeScope().get(0);
    if(!rows.isEmpty()){
      var row=rows.get(0);
      if(!scope.projectId().equals(row.get("project_id"))||!scope.regionId().equals(row.get("region_id"))||!scope.subsystemCode().equals(row.get("subsystem_code")))
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"对象不存在或不在授权范围");
    }
    return new Stamp(root,key,scope,rows.isEmpty());
  }
  public static void requireGlobalConfigurationWriter(){
    var context=current();if(context==null)return;
    if(context.readOnly()||!context.adminLevel().equals("L1_SUPER"))
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"全局配置仅允许平台管理员修改");
  }
  public static ScopeTriple writeScope(){
    var context=current();if(context==null)return null;
    if(context.readOnly()||context.scopeMode()!=ScopeSql.Mode.SCOPED||context.activeScope().size()!=1)
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"写入须选择唯一授权范围");
    return context.activeScope().get(0);
  }
  /** Linked aggregates must belong to the selected write tuple too. */
  public static void requireReference(NamedParameterJdbcTemplate db,Root root,String key){
    if(current()==null||key==null||key.isBlank())return;
    var tuple=writeScope();
    Integer count=db.getJdbcTemplate().queryForObject("SELECT count(*) FROM safety."+root.table+" WHERE "+root.key+"=? AND project_id=? AND region_id=? AND subsystem_code=?",Integer.class,key,tuple.projectId(),tuple.regionId(),tuple.subsystemCode());
    if(count==null||count!=1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"关联对象不存在或不在当前范围");
  }
  public static void afterWrite(NamedParameterJdbcTemplate db,Stamp stamp){
    if(stamp==null||!stamp.created())return;
    int n=db.getJdbcTemplate().update("UPDATE safety."+stamp.root().table+" SET project_id=?,region_id=?,subsystem_code=? WHERE "+stamp.root().key+"=? AND (project_id IS NULL OR project_id='LEGACY') AND (region_id IS NULL OR region_id='UNASSIGNED')",
      stamp.scope().projectId(),stamp.scope().regionId(),stamp.scope().subsystemCode(),stamp.key());
    if(n!=1)throw new IllegalStateException("New root tuple was not stamped atomically");
  }
}
