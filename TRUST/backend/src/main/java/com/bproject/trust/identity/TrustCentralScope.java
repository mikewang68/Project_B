package com.bproject.trust.identity;

import com.bproject.iam.context.*;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Central project/region scope supplements, and never replaces, native organization ownership. */
public final class TrustCentralScope {
    private TrustCentralScope(){}
    private static final ThreadLocal<UserContext> CURRENT=new ThreadLocal<>();
    public static UserContext current(){return CURRENT.get();}
    public static AutoCloseable bind(UserContext context){var old=CURRENT.get();if(context==null)CURRENT.remove();else CURRENT.set(context);return ()->{if(old==null)CURRENT.remove();else CURRENT.set(old);};}
    public static UserContext validate(JsonNode assertion,String subject){
        if(!assertion.hasNonNull("userContext"))return null;
        try{
            var c=ContextRules.validate(Json.MAPPER.treeToValue(assertion.path("userContext"),UserContext.class),subject,"trust");
            if(c.scopeMode()==ScopeSql.Mode.DENY||c.readOnly()!=assertion.path("readOnly").asBoolean())throw new IllegalArgumentException();return c;
        }catch(Exception e){throw new ApiError(403,"中央范围无效");}
    }
    public enum Root {EVENT("events"),EVIDENCE("evidence");final String table;Root(String table){this.table=table;}}
    public static String predicate(Root root,String outer){
        var c=current();if(c==null)return "";
        if(!Set.of("events_read","evidence_read","e","v").contains(outer))throw new IllegalArgumentException("Unregistered SQL alias");
        return " AND EXISTS(SELECT 1 FROM "+root.table+" o WHERE o.id="+outer+".id"+ScopeSql.build(c.scopeMode(),c.activeScope(),ScopeSql.Columns.ORDER).my_info()+") ";
    }
    public static ScopeTriple writeTuple(){
        var c=current();if(c==null)return null;
        if(c.readOnly()||c.scopeMode()!=ScopeSql.Mode.SCOPED||c.activeScope().size()!=1)throw new ApiError(403,"写入须选择唯一授权范围");
        return c.activeScope().get(0);
    }
    public static boolean permits(UserContext c,String method,String path){
        return permits(c,method,path,false);
    }
    public static boolean permits(UserContext c,String method,String path,boolean localReadOnly){
        if(c==null)return true;
        if(Set.of("GET","HEAD").contains(method)&&Set.of("/api/v1/me","/api/v1/csrf","/api/v1/identity-mode").contains(path)
            ||method.equals("POST")&&path.equals("/api/v1/logout"))return true;
        // These append only the authenticated actor's own page receipts. They
        // confer no business permission; result updates check receipt ownership.
        if(method.equals("POST")&&(path.equals("/api/v1/audit/page-actions")
            ||path.matches("/api/v1/audit/page-actions/[0-9a-fA-F-]{36}/result")))return true;
        String menu="TRUST:events",action;
        if(Set.of("GET","HEAD").contains(method)&&path.matches("/api/v1/events(?:/[A-Za-z0-9-]+)?"))action="VIEW";
        else if(Set.of("GET","HEAD").contains(method)&&path.matches("/api/v1/events/[A-Za-z0-9-]+/processing-history"))action="VIEW";
        else if(method.equals("POST")&&path.equals("/api/v1/events"))action="CHAIN_SUBMIT";
        else if(method.equals("POST")&&path.matches("/api/v1/events/[A-Za-z0-9-]+/corrections")){
            if(!c.hasAction(menu,"EDIT"))return false;action="CHAIN_SUBMIT";
        }
        else if(method.equals("POST")&&path.matches("/api/v1/events/[A-Za-z0-9-]+/verify"))action="AUDIT_VERIFY";
        else if(method.equals("POST")&&path.matches("/api/v1/events/[A-Za-z0-9-]+/export"))action="EXPORT";
        else if(method.equals("GET")&&path.matches("/api/v1/evidence/[A-Za-z0-9-]+/download"))action="DOWNLOAD";
        else if(method.equals("POST")&&path.equals("/api/v1/evidence"))action="UPLOAD";
        else if(Set.of("GET","HEAD").contains(method)&&path.equals("/api/v1/tasks")){menu="TRUST:tasks";action="VIEW";}
        else if(method.equals("POST")&&path.matches("/api/v1/events/[A-Za-z0-9-]+/retry")){menu="TRUST:tasks";action="CHAIN_SUBMIT";}
        else if(Set.of("GET","HEAD").contains(method)&&path.equals("/api/v1/trace")){menu="TRUST:trace";action="VIEW";}
        else if(path.equals("/api/v1/identity-management")||path.startsWith("/api/v1/identity-management/")){
            if(!globalManagement(c))return false;
            menu="TRUST:identities";
            String id="[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
            if(Set.of("GET","HEAD").contains(method)&&(path.equals("/api/v1/identity-management")
                    ||path.matches("/api/v1/identity-management/"+id+"/history")
                    ||path.matches("/api/v1/identity-management/"+id+"/certificates(?:/[1-9][0-9]*)?/history")))action="VIEW";
            else return false;
        }
        else if(path.equals("/api/v1/status")||path.equals("/api/v1/audit")||path.startsWith("/api/v1/audit/")){
            if(!globalManagement(c))return false;
            menu="TRUST:status";
            if(!c.hasAction(menu,"VIEW"))return false;
            if(Set.of("GET","HEAD").contains(method)&&Set.of("/api/v1/status","/api/v1/audit",
                    "/api/v1/audit/settings","/api/v1/audit/settings/history","/api/v1/audit/delivery").contains(path))action="VIEW";
            else if(method.equals("PATCH")&&path.equals("/api/v1/audit/settings"))action="EDIT";
            else if(method.equals("POST")&&path.matches("/api/v1/audit/delivery/[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}/retry"))action="EDIT";
            else return false;
        }
        else if(path.equals("/api/v1/wallets")||path.startsWith("/api/v1/wallets/")){
            // Wallets are organization-wide custody records. A regional business
            // role cannot become their administrator through an accidental grant.
            if(!globalManagement(c))return false;
            menu="TRUST:wallets";
            if(!c.hasAction(menu,"VIEW"))return false;
            String id="[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
            if(Set.of("GET","HEAD").contains(method)&&(path.equals("/api/v1/wallets")
                    ||path.matches("/api/v1/wallets/"+id+"/chain-authorization")
                    ||path.matches("/api/v1/wallets/requests/"+id+"/(?:policy|history)")
                    ||path.matches("/api/v1/wallets/"+id+"/history")
                    ||path.matches("/api/v1/wallets/bindings/[A-Za-z0-9][A-Za-z0-9._-]{0,79}/history")))action="VIEW";
            else if(method.equals("POST")&&path.equals("/api/v1/wallets/requests"))action="EDIT";
            else if(method.equals("POST")&&path.matches("/api/v1/wallets/requests/"+id+"/review"))action="CONFIRM";
            else return false;
        }
        else return false;
        return c.hasAction(menu,action)&&(!(c.readOnly()||localReadOnly)||Set.of("VIEW","DOWNLOAD","EXPORT","AUDIT_VERIFY").contains(action));
    }
    private static boolean globalManagement(UserContext c){
        return Set.of("L1_SUPER","L1_READONLY").contains(c.adminLevel())&&c.scopeMode()==ScopeSql.Mode.GLOBAL;
    }
    public static Set<String> permissions(UserContext c,boolean readonly){
        var result=new HashSet<String>();
        for(var p:Map.ofEntries(Map.entry("trust:event:read","/api/v1/events"),Map.entry("trust:task:read","/api/v1/tasks"),
            Map.entry("trust:evidence:verify","/api/v1/events/one/verify"),Map.entry("trust:evidence:export","/api/v1/events/one/export"),
            Map.entry("trust:evidence:download","/api/v1/evidence/one/download"),Map.entry("trust:evidence:upload","/api/v1/evidence"),
            Map.entry("trust:event:submit","/api/v1/events"),Map.entry("trust:event:correct","/api/v1/events/one/corrections"),
            Map.entry("trust:task:retry","/api/v1/events/one/retry"),
            Map.entry("trust:trace:read","/api/v1/trace")).entrySet()){
            String method=Set.of("trust:event:read","trust:task:read","trust:trace:read","trust:evidence:download").contains(p.getKey())?"GET":"POST";
            boolean safe=Set.of("trust:event:read","trust:task:read","trust:trace:read","trust:evidence:download","trust:evidence:export","trust:evidence:verify").contains(p.getKey());
            if((!readonly||safe)&&permits(c,method,p.getValue()))result.add(p.getKey());
        }
        if(permits(c,"GET","/api/v1/wallets"))result.add("trust:wallet:read");
        if(!readonly&&permits(c,"POST","/api/v1/wallets/requests"))result.add("trust:wallet:manage");
        if(!readonly&&permits(c,"POST","/api/v1/wallets/requests/00000000-0000-0000-0000-000000000001/review"))result.add("trust:wallet:review");
        if(permits(c,"GET","/api/v1/identity-management"))result.add("trust:identity:read");
        if(permits(c,"GET","/api/v1/status"))result.add("trust:operations:read");
        if(permits(c,"GET","/api/v1/audit"))result.add("trust:audit:read");
        return Set.copyOf(result);
    }
}
