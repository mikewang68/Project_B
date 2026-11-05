package com.bproject.ehm.platform.security;

import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Request-only central scope; background jobs must bind a separately authorized tuple. */
public final class EhmCentralScope {
    private EhmCentralScope(){}
    private static final ThreadLocal<UserContext> CURRENT=new ThreadLocal<>();
    public static UserContext current(){return CURRENT.get();}
    public static AutoCloseable bind(UserContext context){
        var old=CURRENT.get();if(context==null)CURRENT.remove();else CURRENT.set(context);
        return ()->{if(old==null)CURRENT.remove();else CURRENT.set(old);};
    }
    public static UserContext validate(JsonNode data,String subject,ObjectMapper mapper){
        if(!data.hasNonNull("userContext"))return null;
        try{
            var context=ContextRules.validate(mapper.treeToValue(data.path("userContext"),UserContext.class),subject,"ehms");
            if(context.scopeMode()==ScopeSql.Mode.DENY||context.readOnly()!=data.path("readOnly").asBoolean())throw new IllegalArgumentException();
            return context;
        }catch(Exception invalid){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"SSO_CENTRAL_SCOPE_REJECTED");}
    }
    public static String readPredicate(){
        var c=current();
        // V1 records remain in their historical unassigned region and cannot read new V2 rows.
        return c==null?" AND o.region_id='UNASSIGNED' AND o.subsystem_code='EHMS' ":
            ScopeSql.build(c.scopeMode(),c.activeScope(),ScopeSql.Columns.ORDER).my_info();
    }
    public static ScopeTriple writeTuple(String project){
        var c=current();if(c==null)return new ScopeTriple(project,"UNASSIGNED","EHMS");
        if(c.readOnly()||c.scopeMode()!=ScopeSql.Mode.SCOPED||c.activeScope().size()!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Write requires one authorized tuple");
        var tuple=c.activeScope().get(0);
        if(!project.equals(tuple.projectId())||!"EHMS".equals(tuple.subsystemCode()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Selected project does not match central scope");
        return tuple;
    }
    /** Exact implemented governance endpoints; no prefix grants for future operations. */
    public record GovernanceRoute(String menu,String action,String nativePermission){}
    public static GovernanceRoute governanceRoute(String method,String relative){
        String root=relative.split("/",2)[0],suffix=relative.substring(root.length());
        String menu,nativeRoot;
        if(relative.startsWith("reliability/")){
            String rest=relative.substring("reliability/".length());
            root=rest.split("/",2)[0];suffix=rest.substring(root.length());
            menu=switch(root){case "failure-modes"->"fmeca";case "fault-codes"->"fault-codes";
                case "alarm-rules"->"alarm-rules";case "knowledge-cases"->"knowledge";case "sla-policies"->"sla";default->null;};
            nativeRoot=switch(root){case "failure-modes"->"ehealth:asset:fmeca";case "fault-codes"->"ehealth:alert:code";
                case "alarm-rules"->"ehealth:alert:rule";case "knowledge-cases"->"ehealth:alert:kb";case "sla-policies"->"ehealth:alert:sla";default->null;};
        }else{
            menu=switch(root){case "device-templates"->"device-template";case "calibration-records"->"sensor-cal";
                case "configuration-changes"->"config-change";case "my-tasks"->"my-tasks";case "shift-handovers"->"shift-handover";default->null;};
            nativeRoot=switch(root){case "device-templates"->"ehealth:asset:template";case "calibration-records"->"ehealth:asset:sensor";
                case "configuration-changes"->"ehealth:asset:history";case "my-tasks"->"ehealth:wb:todo";case "shift-handovers"->"ehealth:wb:shift";default->null;};
        }
        if(menu==null)return null;
        String action=null;
        if(Set.of("GET","HEAD").contains(method)&&suffix.isEmpty())action="VIEW";
        else if(method.equals("POST")&&suffix.isEmpty()&&!Set.of("fault-codes","sla-policies").contains(root))action="ADD";
        else if(method.equals("PUT")&&suffix.matches("/[^/]+")&&Set.of("device-templates","my-tasks","shift-handovers","failure-modes","sla-policies").contains(root))action="EDIT";
        else if(method.equals("POST")){
            if(root.equals("shift-handovers")&&suffix.equals("/generate"))action="ADD";
            else if(root.equals("device-templates")&&suffix.matches("/[^/]+/publish")
                ||root.equals("configuration-changes")&&suffix.matches("/[^/]+/(approve|apply)")
                ||root.equals("my-tasks")&&suffix.matches("/[^/]+/complete")
                ||root.equals("shift-handovers")&&suffix.matches("/[^/]+/(submit|receive)")
                ||root.equals("alarm-rules")&&suffix.matches("/[^/]+/publish")
                ||root.equals("knowledge-cases")&&suffix.matches("/[^/]+/verify"))action="CONFIRM";
            else if(root.equals("alarm-rules")&&suffix.matches("/[^/]+/disable"))action="EDIT";
        }
        if(action==null)return null;
        String nativeAction=action.equals("VIEW")?"view":root.equals("my-tasks")?"execute":
            action.equals("ADD")?"add":root.equals("alarm-rules")&&action.equals("CONFIRM")?"execute":"edit";
        return new GovernanceRoute("EHMS:"+menu,action,nativeRoot+":"+nativeAction);
    }
    public static boolean permits(UserContext c,String method,String path){
        if(c==null)return true;
        if(path.equals("/api/ehm/v1/auth/me")&&Set.of("GET","HEAD").contains(method)
            ||path.equals("/api/ehm/v1/auth/logout")&&method.equals("POST"))return true;
        String relative=path.replaceFirst("^/api/ehm/v1/","");
        var governance=governanceRoute(method,relative);
        if(governance!=null)return c.hasAction(governance.menu(),governance.action())
            &&(!c.readOnly()||governance.action().equals("VIEW"));
        if(relative.matches("shared-dispatch/(plans|work-orders)(/[-_a-zA-Z0-9]{1,128})?"))
            return Set.of("GET","HEAD").contains(method)&&c.hasAction("EHMS:workorders","VIEW");
        boolean telemetry=relative.matches("telemetry/points/[^/]+/samples");
        boolean qualityRead=Set.of("data-quality/points","data-quality/summary").contains(relative);
        boolean qualityWrite=relative.matches("data-quality/points/[^/]+/sample");
        if(telemetry)return Set.of("GET","HEAD").contains(method)&&c.hasAction("EHMS:realtime","VIEW");
        if(qualityRead)return Set.of("GET","HEAD").contains(method)&&c.hasAction("EHMS:data-quality","VIEW");
        if(qualityWrite)return method.equals("POST")&&!c.readOnly()&&c.hasAction("EHMS:data-quality","EDIT");
        String menu=relative.matches("devices(?:/.*)?")?"EHMS:fleet":
            relative.matches("work-orders(?:/.*)?")?"EHMS:workorders":
            relative.matches("alarms(?:/.*)?")?"EHMS:alarm-center":
            Set.of("dashboard/summary","analytics/overview").contains(relative)?"EHMS:cockpit":null;
        if(menu==null)return false;
        boolean safe=Set.of("GET","HEAD").contains(method);
        String action=relative.matches(".*/export(?:/.*)?")?"EXPORT":relative.matches(".*/download(?:/.*)?")?"DOWNLOAD":
            safe?"VIEW":method.equals("DELETE")?"DELETE":
            relative.matches("work-orders/[^/]+/(approve|reject|status)")?"CONFIRM":
            method.equals("POST")&&Set.of("devices","work-orders").contains(relative)?"ADD":"EDIT";
        return c.hasAction(menu,action)&&(!c.readOnly()||Set.of("VIEW","EXPORT","DOWNLOAD","AUDIT_VERIFY").contains(action));
    }
}
