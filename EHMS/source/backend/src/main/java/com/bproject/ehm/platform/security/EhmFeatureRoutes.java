package com.bproject.ehm.platform.security;

import com.bproject.iam.context.UserContext;
import java.util.*;

/** Explicit contracts for restored features, not a prefix-wide grant to old endpoints. */
public final class EhmFeatureRoutes {
    private EhmFeatureRoutes(){}
    private static final String ANALYTICS="cockpit,area-map,kpi,reliability,oee,failure-report,maintenance-cost,coverage-report,resource-forecast,energy-health,period-report";
    public record Rule(List<String> menus,String action,String nativePermission) {
        public boolean permits(UserContext c){return (!c.readOnly()||Set.of("VIEW","EXPORT").contains(action))
                &&menus.stream().anyMatch(menu->c.hasAction("EHMS:"+menu,action));}
        public boolean nativeAllowed(EhmSsoSessions.Actor actor){
            if(actor.context()==null||!action.equals("VIEW"))return actor.permissions().contains(nativePermission);
            return menus.stream().anyMatch(menu->actor.context().hasAction("EHMS:"+menu,action)
                &&actor.permissions().contains(viewPermission(menu,nativePermission)));
        }
    }
    private static String viewPermission(String menu,String fallback){return switch(menu){
        case "health"->"ehealth:health:score:view";case "baseline"->"ehealth:health:baseline:view";
        case "degradation"->"ehealth:health:trend:view";case "rul"->"ehealth:health:rul:view";
        case "maintenance-advice"->"ehealth:health:suggest:view";case "strategy"->"ehealth:maint:strategy:view";
        case "maintenance-plan"->"ehealth:maint:plan:view";case "inspection"->"ehealth:maint:check:view";
        case "evidence"->"ehealth:alert:evidence:view";case "rootcause"->"ehealth:alert:rca:view";
        case "repair-retest"->"ehealth:maint:record:view";case "asset-tree"->"ehealth:asset:tree:view";
        case "area-map"->"ehealth:rt:dist:view";case "diagnosis"->"ehealth:rt:diagnosis:view";
        case "kpi"->"ehealth:report:kpi:view";case "reliability"->"ehealth:report:reliability:view";
        case "oee"->"ehealth:report:oee:view";case "failure-report"->"ehealth:report:fault:view";
        case "maintenance-cost"->"ehealth:report:cost:view";case "coverage-report"->"ehealth:report:coverage:view";
        case "resource-forecast"->"ehealth:report:forecast:view";case "energy-health"->"ehealth:report:energy:view";
        case "period-report"->"ehealth:report:period:view";
        default->fallback;};}
    private static Rule rule(String menus,String action,String permission){return new Rule(List.of(menus.split(",")),action,permission);}
    public static Rule resolve(String method,String path){
        if(!path.startsWith("/api/ehm/v1/"))return null;
        String p=path.substring("/api/ehm/v1/".length());boolean read=Set.of("GET","HEAD").contains(method);
        if(p.matches("devices/[^/]+/dossier(?:/maintenance-due|/documents(?:/[^/]+(?:/content)?)?)?")) {
            if(read)return rule("fleet,asset-tree,asset-profile","VIEW","ehealth:asset:archive:view");
            if(method.equals("PUT")&&p.matches("devices/[^/]+/dossier"))return rule("fleet","EDIT","ehealth:asset:archive:edit");
            if(method.equals("POST")&&p.matches("devices/[^/]+/dossier/documents"))return rule("fleet","EDIT","ehealth:asset:archive:edit");
            if(method.equals("DELETE")&&p.matches("devices/[^/]+/dossier/documents/[^/]+"))return rule("fleet","DELETE","ehealth:asset:archive:edit");
            return null;
        }
        if(read&&p.matches("devices(?:/[^/]+)?")&&!p.matches("devices/(export|download|backup)"))
            return rule("fleet,asset-tree,asset-profile","VIEW","ehealth:asset:archive:view");
        if(p.equals("analytics/overview")&&read)return rule(ANALYTICS,"VIEW","ehealth:wb:cockpit:view");
        if(p.matches("telemetry/points/[^/]+/samples")&&read)return rule("realtime,diagnosis","VIEW","ehealth:rt:realtime:view");
        if(p.matches("telemetry/points/[^/]+/observations/[a-f0-9]{64}")&&read)return rule("realtime,diagnosis","VIEW","ehealth:rt:realtime:view");
        if(p.matches("assistant/(status|models|sessions/[^/]+)")&&read)
            return rule("ai-assistant","VIEW","ehealth:wb:assistant:view");
        if(p.matches("assistant/(chat|test)")&&method.equals("POST"))
            return rule("ai-assistant","VIEW","ehealth:wb:assistant:view"); // read-only tool, CSRF still mandatory
        if(p.matches("assistant/sessions/[^/]+")&&method.equals("DELETE"))
            return rule("ai-assistant","VIEW","ehealth:wb:assistant:view"); // only the caller's own conversation
        if(p.equals("system/activity")&&read)return rule(ANALYTICS+",sample-workbench","VIEW","ehealth:wb:cockpit:view");
        if(p.equals("system/audit-logs")&&read)return rule("audit-logs","VIEW","ehealth:system:log:view");
        if(p.matches("assets/[^/]+/components|components/[^/]+")){
            if(read)return rule("bom,asset-tree","VIEW","ehealth:asset:bom:view");
            if(method.equals("POST")&&p.matches("assets/[^/]+/components"))return rule("bom","ADD","ehealth:asset:bom:add");
            if(method.equals("PUT")&&p.matches("components/[^/]+"))return rule("bom","EDIT","ehealth:asset:bom:edit");
            if(method.equals("DELETE")&&p.matches("components/[^/]+"))return rule("bom","DELETE","ehealth:asset:bom:delete");
        }
        if(p.matches("assets/[^/]+/measurement-points|measurement-points/[^/]+")){
            if(read)return rule("measurement","VIEW","ehealth:asset:point:view");
            if(method.equals("POST")&&p.startsWith("assets/"))return rule("measurement","ADD","ehealth:asset:point:add");
            if(method.equals("PUT")&&p.startsWith("measurement-points/"))return rule("measurement","EDIT","ehealth:asset:point:edit");
            if(method.equals("DELETE")&&p.startsWith("measurement-points/"))return rule("measurement","DELETE","ehealth:asset:point:delete");
        }
        if(p.matches("devices/[^/]+/health-assessments(?:/latest)?")&&read)
            return rule("health,baseline,degradation,rul,maintenance-advice","VIEW","ehealth:health:score:view");
        if(p.matches("devices/[^/]+/health-summary")&&read)return rule("health","VIEW","ehealth:health:score:view");
        if(p.matches("devices/[^/]+/maintenance-reminders")&&read)return rule("maintenance-plan","VIEW","ehealth:maint:plan:view");
        if(p.matches("devices/[^/]+/maintenance-reminders/scan")&&method.equals("POST"))return rule("maintenance-plan","ADD","ehealth:maint:check:execute");
        if(p.matches("maintenance-reminders/[^/]+/complete")&&method.equals("POST"))return rule("inspection","CONFIRM","ehealth:maint:check:execute");
        if(p.matches("work-orders/[^/]+/repair")&&read)return rule("repair-retest","VIEW","ehealth:maint:record:view");
        if(p.matches("work-orders/[^/]+/repair/(plan|executions|retests|close)")&&method.equals("POST"))return rule("repair-retest","CONFIRM","ehealth:maint:record:execute");
        if(p.matches("devices/[^/]+/health-assessments/(run|replay)")&&method.equals("POST"))
            return rule("health","EDIT","ehealth:health:score:execute");
        if(p.matches("health-assessments/[^/]+/review")&&method.equals("POST"))
            return rule("maintenance-advice","CONFIRM","ehealth:health:suggest:execute");
        if(p.matches("health-assessments/[^/]+/work-order")&&method.equals("POST"))
            return rule("maintenance-advice","ADD","ehealth:maint:order:execute");
        if(p.equals("maintenance-plans")&&read)return rule("strategy,maintenance-plan,inspection","VIEW","ehealth:maint:plan:view");
        if(p.equals("maintenance-plans")&&method.equals("POST"))return rule("strategy","ADD","ehealth:maint:strategy:add");
        if(p.matches("maintenance-plans/[^/]+/inspection-tasks")&&method.equals("POST"))return rule("maintenance-plan","ADD","ehealth:maint:check:execute");
        if(p.matches("inspection-tasks(?:/[^/]+)?|maintenance-defects")&&read)return rule("inspection,strategy,maintenance-plan","VIEW","ehealth:maint:check:view");
        if(p.matches("inspection-tasks/[^/]+/start")&&method.equals("PATCH"))return rule("inspection","EDIT","ehealth:maint:check:execute");
        if(p.matches("inspection-tasks/[^/]+/submit")&&method.equals("POST"))return rule("inspection","CONFIRM","ehealth:maint:check:execute");
        if(p.matches("maintenance-defects/[^/]+/work-order")&&method.equals("POST"))return rule("inspection","ADD","ehealth:maint:order:execute");
        if(p.matches("(?:alarms|work-orders)/[^/]+/case")&&read)return rule("evidence,rootcause,repair-retest","VIEW","ehealth:alert:evidence:view");
        if(p.matches("alarms/[^/]+/case(?:/diagnoses)?")&&method.equals("POST"))return rule("rootcause","EDIT","ehealth:alert:rca:edit");
        if(p.matches("case-evidence-windows/[^/]+")&&read)return rule("evidence,rootcause","VIEW","ehealth:alert:evidence:view");
        if(p.matches("alarms/[^/]+/case/(evidence-windows|diagnoses/[^/]+/revisions)")&&method.equals("POST"))return rule("rootcause","EDIT","ehealth:alert:rca:edit");
        if(p.matches("alarms/[^/]+/case/work-order")&&method.equals("POST"))return rule("rootcause","ADD","ehealth:maint:order:execute");
        if(p.matches("work-orders/[^/]+/case/(execution-records|retests|close|retest-plan)")&&method.equals("POST"))
            return rule("repair-retest","CONFIRM","ehealth:maint:record:execute");
        if(p.matches("spare-parts|spare-stock/(summary|alerts|transactions)|spare-reservations")&&read)
            return rule("spares","VIEW","ehealth:maint:spare:view");
        if(p.equals("spare-parts")&&method.equals("POST"))return rule("spares","ADD","ehealth:maint:spare:execute");
        if(p.matches("spare-parts/[^/]+/(receipt|return)|spare-reservations/[^/]+/(issue|release)|work-orders/[^/]+/spare-reservations")&&method.equals("POST"))
            return rule("spares","EDIT","ehealth:maint:spare:execute");
        return null;
    }
}
