package com.mt.wms.auth.sso;

import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Central tuple is an additional ceiling on native company/warehouse/owner isolation. */
public final class WmsCentralScope {
  private WmsCentralScope() {}
  private static final String ATTRIBUTE=WmsCentralScope.class.getName();
  private static final Set<String> READONLY=Set.of("VIEW","DOWNLOAD","EXPORT","AUDIT_VERIFY");
  public static UserContext validate(JsonNode assertion,ObjectMapper mapper) {
    var raw=assertion.get("userContext");
    if(raw==null||raw.isNull())return null;
    try {
      var c=ContextRules.validate(mapper.treeToValue(raw,UserContext.class),
          assertion.path("subject").asText(),"wms");
      if(c.scopeMode()==ScopeSql.Mode.DENY||c.readOnly()!=assertion.path("readOnly").asBoolean())
        throw new SecurityException("Central scope denied");
      return c;
    }catch(Exception e){throw new SecurityException("Invalid central scope");}
  }
  public static void bind(UserContext context) {
    if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
      bind(attrs.getRequest(),context);
    }else if(context!=null)throw new SecurityException("Central request context unavailable");
  }
  public static void bind(jakarta.servlet.http.HttpServletRequest request,UserContext context) {
    if(context==null)request.removeAttribute(ATTRIBUTE);
    else request.setAttribute(ATTRIBUTE,context);
  }
  public static UserContext current() {
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
        ? (UserContext)attrs.getRequest().getAttribute(ATTRIBUTE):null;
  }
  public static UserContext current(jakarta.servlet.http.HttpServletRequest request) {
    return (UserContext)request.getAttribute(ATTRIBUTE);
  }
  public static ScopeSql.Filters warehouseFilter() {
    var c=current();
    return c==null?null:ScopeSql.build(c.scopeMode(),c.activeScope(),ScopeSql.Columns.ORDER);
  }
  public static ScopeTriple concreteWriteScope() {
    var c=current();
    if(c==null)return null;
    if(c.readOnly()||c.scopeMode()!=ScopeSql.Mode.SCOPED||c.activeScope().size()!=1
        ||!"WMS".equals(c.activeScope().get(0).subsystemCode()))
      throw new org.springframework.security.access.AccessDeniedException("请选择唯一且已授权的仓库归属");
    return c.activeScope().get(0);
  }
  public static boolean localPermission(UserContext c,String permission) {
    if(permission.equals("tenant:all"))return true; // Native visibility is still filtered by warehouseFilter.
    // V2 personnel administration is performed by IAM; this only exposes its
    // navigation entry. Native user CRUD routes remain denied below.
    if(permission.equals("system:user:read"))return Set.of("L1_SUPER","L1_READONLY","L2_PROJECT","L3_SUBSYSTEM")
        .contains(c.adminLevel())&&allowed(c,"WMS:users","VIEW");
    if(permission.equals("integration:manage"))return Set.of("VIEW","UPLOAD","EXPORT","DOWNLOAD").stream()
        .anyMatch(op->allowed(c,"WMS:integration",op));
    if(permission.startsWith("agent:"))return allowed(c,"WMS:warehouse-agent","VIEW")
        &&allowed(c,"WMS:inventory","VIEW")&&(permission.equals("agent:read")
          ||permission.equals("agent:manage")&&Set.of("ADD","EDIT","FAULT_ALARM","RECEIVE_EMAIL").stream()
            .anyMatch(op->allowed(c,"WMS:warehouse-agent",op)));
    String prefix=permission.split(":")[0];
    String menu=switch(prefix) {
      case "master" -> "WMS:master-data";
      case "stockin","stockout","inventory","finance","dashboard","integration" -> "WMS:"+prefix;
      default -> null;
    };
    if(menu==null)return false;
    if(permission.endsWith(":read")||permission.endsWith(":view"))return allowed(c,menu,"VIEW");
    return Set.of("ADD","EDIT","DELETE","CONFIRM","UPLOAD","EXPORT","DOWNLOAD").stream()
        .anyMatch(op->allowed(c,menu,op));
  }
  private static boolean allowed(UserContext c,String menu,String op) {
    return c.hasAction(menu,op)&&(!c.readOnly()||READONLY.contains(op));
  }
  public static boolean route(UserContext c,String method,String path) {
    if(path.matches("/api/v1/shared-dispatch/(plans|work-orders)(/[-_a-zA-Z0-9]{1,128})?"))
      return Set.of("GET","HEAD").contains(method)&&allowed(c,"WMS:stockin","VIEW");
    if(Set.of("GET","HEAD").contains(method)&&Set.of("/api/v1/auth/me","/api/v1/auth/tenant").contains(path)
        ||method.equals("PUT")&&path.equals("/api/v1/auth/tenant"))return true;
    if(path.equals("/api/v1/dashboard/summary")&&Set.of("GET","HEAD").contains(method))
      return allowed(c,"WMS:dashboard","VIEW");
    if(path.startsWith("/api/v1/agent/")) {
      String suffix=path.substring("/api/v1/agent".length());
      if(!allowed(c,"WMS:warehouse-agent","VIEW")||!allowed(c,"WMS:inventory","VIEW"))return false;
      if(Set.of("GET","HEAD").contains(method)) {
        if(suffix.equals("/weights"))return allowed(c,"WMS:master-data","VIEW");
        return Set.of("/status","/history","/utilization","/alerts","/schedules","/mails").contains(suffix);
      }
      if(method.equals("PUT")&&suffix.matches("/weights/[0-9]+"))
        return c.adminLevel().equals("L1_SUPER")&&allowed(c,"WMS:master-data","EDIT");
      if(c.scopeMode()!=ScopeSql.Mode.SCOPED||c.activeScope().size()!=1
          ||!"WMS".equals(c.activeScope().get(0).subsystemCode()))return false;
      if(method.equals("POST")&&Set.of("/chat","/schedules").contains(suffix))
        return allowed(c,"WMS:warehouse-agent","ADD");
      if(method.equals("PUT")&&suffix.matches("/schedules/[0-9]+")
          ||method.equals("POST")&&suffix.matches("/alerts/[0-9]+/read"))
        return allowed(c,"WMS:warehouse-agent","EDIT");
      if(method.equals("POST")&&suffix.matches("/schedules/[0-9]+/run"))
        return allowed(c,"WMS:warehouse-agent","FAULT_ALARM");
      if(method.equals("POST")&&suffix.matches("/mails/[0-9]+/retry"))
        return allowed(c,"WMS:warehouse-agent","FAULT_ALARM")&&allowed(c,"WMS:warehouse-agent","RECEIVE_EMAIL");
      return false;
    }
    if(path.equals("/api/v1/integration/tasks")&&Set.of("GET","HEAD").contains(method))
      return allowed(c,"WMS:integration","VIEW");
    if(method.equals("POST")&&path.matches("/api/v1/integration/tasks/(export|import)/(?:GOODS|PARTNERS|INVENTORY|STOCKIN|STOCKOUT|FINANCE|goods|partners|inventory|stockin|stockout|finance)"))
      return allowed(c,"WMS:integration",path.contains("/export/")?"EXPORT":"UPLOAD");
    if(Set.of("GET","HEAD").contains(method)&&(path.matches("/api/v1/integration/tasks/[0-9]+/download")
        ||path.matches("/api/v1/integration/templates/[A-Za-z]+")))return allowed(c,"WMS:integration","DOWNLOAD");
    if(path.matches("/api/v1/master-data/(warehouses|owners|areas|work-areas|locations|partners|categories|goods|components)(/[^/]+)?")) {
      String[] parts=path.split("/");
      String kind=parts[4];
      String action=switch(method) {
        case "GET","HEAD" -> parts.length==5?"VIEW":null;
        case "POST" -> parts.length==5?"ADD":null;
        case "PUT" -> parts.length==6?"EDIT":null;
        case "DELETE" -> parts.length==6?"DELETE":null;
        default -> null;
      };
      boolean companyCatalog=Set.of("owners","partners","categories","goods","components").contains(kind);
      return action!=null&&(!companyCatalog||action.equals("VIEW")||c.adminLevel().equals("L1_SUPER"))
          &&allowed(c,"WMS:master-data",action);
    }
    String resource=null;
    for(String name:List.of("stockin","stockout","inventory","finance"))
      if(path.equals("/api/v1/"+name)||path.startsWith("/api/v1/"+name+"/"))resource=name;
    // More resources can be enabled only after their repository scope is verified.
    if(resource==null)return false;
    String op;
    if(Set.of("GET","HEAD").contains(method))op="VIEW";
    else if(method.equals("POST")) {
      String suffix=path.substring(("/api/v1/"+resource).length());
      if(resource.equals("stockin")) {
        if(suffix.equals("/quick"))return allowed(c,"WMS:stockin","ADD")&&allowed(c,"WMS:stockin","EDIT");
        if(Set.of("","/").contains(suffix))op="ADD";
        else if(suffix.matches("/psms-tasks/[^/]+/confirm"))op="ADD";
        else if(suffix.matches("/psms-tasks/[^/]+/retry-delivery"))op="EDIT";
        else if(suffix.matches("/[^/]+/complete"))op="CONFIRM";
        else if(suffix.matches("/[^/]+/(receipts|scan|cancel)"))op="EDIT";
        else return false;
      }else if(resource.equals("stockout")) {
        if(Set.of("","/","/waves").contains(suffix))op="ADD";
        else if(suffix.matches("/[^/]+/(ship|complete)"))op="CONFIRM";
        else if(suffix.matches("/[^/]+/(allocate|pick|pick-all|packages|cancel-allocation|cancel|reverse)")
            ||suffix.matches("/waves/[^/]+/(allocate|pick)"))op="EDIT";
        else return false;
      }else if(resource.equals("finance")) {
        if(Set.of("/money","/accounts").contains(suffix))op="ADD";
        else if(suffix.matches("/money/[^/]+/transactions"))op="CONFIRM";
        else if(suffix.matches("/money/[^/]+/cancel"))op="EDIT";
        else return false;
      }else {
        if(Set.of("/counts","/serials","/replenishment-rules").contains(suffix))op="ADD";
        else if(Set.of("/adjustments","/moves","/freeze","/unfreeze","/replenishments/generate").contains(suffix))op="EDIT";
        else return false;
      }
    }else if(method.equals("PUT")&&resource.equals("inventory")
        &&path.matches("/api/v1/inventory/replenishment-rules/[^/]+"))op="EDIT";
    else if(method.equals("PUT")&&resource.equals("finance")
        &&path.matches("/api/v1/finance/(money|accounts)/[^/]+"))op="EDIT";
    else return false;
    return allowed(c,"WMS:"+resource,op);
  }
  public static boolean readonlyTransfer(String method,String path) {
    return readonlyTransfer(current(),method,path);
  }
  public static boolean readonlyTransfer(UserContext context,String method,String path) {
    return context!=null&&route(context,method,path)
        &&(method.equals("POST")&&path.startsWith("/api/v1/integration/tasks/export/")
           ||Set.of("GET","HEAD").contains(method)&&path.startsWith("/api/v1/integration/")
              &&(path.endsWith("/download")||path.startsWith("/api/v1/integration/templates/")));
  }
}
