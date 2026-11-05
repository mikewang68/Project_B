package com.bdemo.config;

import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Native energy zones and IAM regions are different dimensions. Both must be enforced. */
public final class EmcsCentralScope {
    private EmcsCentralScope(){}
    private static final ThreadLocal<UserContext> CURRENT=new ThreadLocal<>();
    public static UserContext current(){return CURRENT.get();}
    public static AutoCloseable bind(UserContext context){var old=CURRENT.get();if(context==null)CURRENT.remove();else CURRENT.set(context);return ()->{if(old==null)CURRENT.remove();else CURRENT.set(old);};}
    public static UserContext validate(JsonNode assertion,String subject,ObjectMapper mapper){
        if(!assertion.hasNonNull("userContext"))return null;
        try{
            var c=ContextRules.validate(mapper.treeToValue(assertion.path("userContext"),UserContext.class),subject,"emcs");
            if(c.scopeMode()==ScopeSql.Mode.DENY||c.readOnly()!=assertion.path("readOnly").asBoolean())throw new IllegalArgumentException();return c;
        }catch(Exception e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid central scope");}
    }
    public static String area(String column){
        if(current()==null)return "";
        if(!Set.of("eq.area_id","a.area_id","e.area_id","p.area_id","s.area_id","area_id","object_id","c.object_id").contains(column))throw new IllegalArgumentException("Unregistered area column");
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean dispatch=auth!=null&&auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_dispatch"));
        return " AND "+column+" IN (SELECT o.area_id FROM e_area o WHERE 1=1"+ScopeSql.build(current().scopeMode(),current().activeScope(),ScopeSql.Columns.ORDER).my_info()
            +(dispatch?" AND o.area_code = 'AREA-A'":"")+") ";
    }
    /** Historical recompute hints without an attributable point remain outside regional access. */
    public static String recomputePredicate(){
        return current()==null?"":" AND EXISTS (SELECT 1 FROM e_meter_point p WHERE p.point_code=(CAST(target_key_json AS jsonb)->>'pointCode')"+area("p.area_id")+") ";
    }
    public static String collectionPredicate(){
        return current()==null?"":" AND EXISTS (SELECT 1 FROM e_area a WHERE a.area_code=d.area_code"+area("a.area_id")+") ";
    }
    public static String costPredicate(String alias){
        if(current()==null)return "";
        if(!Set.of("","c.").contains(alias))throw new IllegalArgumentException("Unregistered cost alias");
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean dispatch=auth!=null&&auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_dispatch"));
        if(current().global()&&!dispatch)return "";
        return " AND (("+alias+"object_type='area'"+area(alias+"object_id")+") OR ("+alias+"object_type='equipment' AND "+alias+"object_id IN (SELECT e.equipment_id FROM e_equipment e WHERE 1=1"+area("e.area_id")+"))) ";
    }
    /** New regional snapshots use one trusted tuple; explicit global jobs retain null ownership. */
    public static void writeRecordScope(java.sql.PreparedStatement statement,int first) throws java.sql.SQLException {
        var c=current();ScopeTriple tuple=null;
        if(c!=null){if(c.global())requireGlobalManagement();else tuple=writeTuple();}
        statement.setString(first,tuple==null?null:tuple.projectId());
        statement.setString(first+1,tuple==null?null:tuple.regionId());
        statement.setString(first+2,tuple==null?null:tuple.subsystemCode());
    }
    public static String archivePredicate(){
        return current()==null?"":ScopeSql.build(current().scopeMode(),current().activeScope()).my_info();
    }
    public static void requireGlobalManagement(){
        var c=current();if(c!=null&&(c.readOnly()||!"L1_SUPER".equals(c.adminLevel())||c.scopeMode()!=ScopeSql.Mode.GLOBAL))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Global dictionaries require global administrator scope");
    }
    public static ScopeTriple writeTuple(){
        var c=current();if(c==null||c.readOnly()||c.scopeMode()!=ScopeSql.Mode.SCOPED||c.activeScope().size()!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Write requires one authorized tuple");
        return c.activeScope().get(0);
    }
    public static Set<String> nativePermissions(Set<String> permissions,UserContext context){
        if(context==null)return Set.copyOf(permissions);
        var effective=new HashSet<String>();
        if(permissions.contains("energy:analysis:profile")&&context.hasAction("EMCS:2004","VIEW"))
            effective.add("energy:analysis:profile");
        if(permissions.contains("energy:cost:report")&&
            (context.hasAction("EMCS:2014","VIEW")||context.hasAction("EMCS:2014","EXPORT")
                ||!context.readOnly()&&context.hasAction("EMCS:2014","ADD")))
            effective.add("energy:cost:report");
        for(var entry:Map.of("energy:overview:view","EMCS:2001","energy:collect:raw:view","EMCS:2008","energy:alert:list","EMCS:2010","energy:alert:suggestion","EMCS:2011","energy:cost:record","EMCS:2013","energy:agent:inspection","EMCS:2015").entrySet())
            if(permissions.contains(entry.getKey())&&(context.hasAction(entry.getValue(),"VIEW")||!context.readOnly()&&Set.of("ADD","EDIT","CONFIRM").stream().anyMatch(a->context.hasAction(entry.getValue(),a))))effective.add(entry.getKey());
        return Set.copyOf(effective);
    }
    public static boolean permits(UserContext c,String method,String path){
        if(c==null)return true;
        if(Set.of("GET","HEAD").contains(method)&&Set.of("/getInfo","/getRouters","/auth/sso/csrf","/transport/crypto/frontend-config","/transport/crypto/public-key").contains(path)
            ||method.equals("POST")&&path.equals("/logout"))return true;
        String menu,action;
        if(Set.of("GET","HEAD").contains(method)&&path.equals("/overview/summary")){menu="EMCS:2001";action="VIEW";}
        else if(path.equals("/raw-quality/summary")&&Set.of("GET","HEAD").contains(method)){menu="EMCS:2008";action="VIEW";}
        else if(Set.of("GET","HEAD").contains(method)&&Set.of("/collection/catalog","/collection/events","/collection/event").contains(path)){menu="EMCS:2008";action="VIEW";}
        else if(method.equals("POST")&&path.equals("/raw-quality/backfill")){menu="EMCS:2008";action="EDIT";}
        else if(method.equals("POST")&&path.equals("/raw-quality/recompute")){menu="EMCS:2008";action="CONFIRM";}
        else if(Set.of("GET","HEAD").contains(method)&&path.matches("/alerts(?:/(?:statistics|[0-9]+))?")){menu="EMCS:2010";action="VIEW";}
        else if(method.equals("POST")&&path.matches("/alerts/[0-9]+/transition"))
            return !c.readOnly()&&(c.hasAction("EMCS:2010","EDIT")||c.hasAction("EMCS:2010","CONFIRM")); // Body-specific gate in EmcsAlertPermissions.
        else if(Set.of("GET","HEAD").contains(method)&&path.matches("/(?:suggestions(?:/(?:retrospective|[0-9]+))?|suggestion-templates)")){menu="EMCS:2011";action="VIEW";}
        else if(method.equals("POST")&&(path.equals("/suggestions")||path.matches("/alerts/[0-9]+/suggestions"))){menu="EMCS:2011";action="ADD";}
        else if(method.equals("POST")&&path.matches("/suggestions/[0-9]+/activities")){menu="EMCS:2011";action="EDIT";}
        else if(method.equals("POST")&&path.matches("/suggestions/[0-9]+/(?:transition|verification/generate)")){menu="EMCS:2011";action="CONFIRM";}
        else if(method.equals("POST")&&path.equals("/suggestion-templates")){menu="EMCS:2011";action="ADD";}
        else if(method.equals("PUT")&&path.matches("/suggestion-templates/[0-9]+")){menu="EMCS:2011";action="EDIT";}
        else if(Set.of("GET","HEAD").contains(method)&&path.matches("/cost/(?:month-view|trace|tariffs|allocation-rules|recomputations(?:/[0-9]+)?)")){menu="EMCS:2013";action="VIEW";}
        else if(method.equals("POST")&&Set.of("/cost/tariffs","/cost/allocation-rules","/cost/recomputations").contains(path)){menu="EMCS:2013";action="ADD";}
        else if(method.equals("POST")&&path.matches("/cost/recomputations/[0-9]+/review")){menu="EMCS:2013";action="CONFIRM";}
        else if(Set.of("GET","HEAD").contains(method)&&path.matches("/agent/inspections(?:/[0-9]+)?")){menu="EMCS:2015";action="VIEW";}
        else if(method.equals("POST")&&path.equals("/agent/chat")){menu="EMCS:2015";action="VIEW";}
        else if(method.equals("POST")&&path.equals("/agent/inspections/run")){menu="EMCS:2015";action="ADD";}
        else if(Set.of("GET","HEAD").contains(method)&&path.matches("/equipment-profiles(?:/[^/]+)?")){menu="EMCS:2004";action="VIEW";}
        else if(path.startsWith("/reports/")){
            menu="EMCS:2014";
            if(method.equals("POST")&&path.equals("/reports/preview"))action="VIEW";
            else if(method.equals("POST")&&path.equals("/reports/export")||method.equals("GET")&&path.matches("/reports/archives/[0-9]+/export"))action="EXPORT";
            else if(method.equals("GET")&&path.matches("/reports/(templates|archives(?:/[0-9]+)?)"))action="VIEW";
            else if(method.equals("POST")&&path.equals("/reports/archives"))action="ADD";
            else return false;
        }else return false;
        return c.hasAction(menu,action)&&(!c.readOnly()||Set.of("VIEW","EXPORT","DOWNLOAD","AUDIT_VERIFY").contains(action));
    }
}
