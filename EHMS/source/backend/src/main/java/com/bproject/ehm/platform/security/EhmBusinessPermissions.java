package com.bproject.ehm.platform.security;

import java.util.Set;
/** Per-operation access for business users; project scope remains enforced by the session filter. */
public final class EhmBusinessPermissions {
    private EhmBusinessPermissions(){}
    public static boolean permitsWorkOrderStatus(EhmSsoSessions.Actor actor,String status){
        if(actor.readOnly())return false;
        if(actor.admin())return true;
        var target=com.bproject.ehm.maintenance.domain.model.WorkOrderStatus.from(status);
        String operation=Set.of(com.bproject.ehm.maintenance.domain.model.WorkOrderStatus.APPROVED,
            com.bproject.ehm.maintenance.domain.model.WorkOrderStatus.REJECTED).contains(target)?"approve":"execute";
        return actor.permissions().contains("ehealth:maint:order:"+operation);
    }
    public static boolean permits(EhmSsoSessions.Actor actor,String method,String path){
        boolean read=Set.of("GET","HEAD").contains(method);
        if(path.equals("/api/ehm/v1/auth/me"))return read;
        if(path.equals("/api/ehm/v1/auth/logout")||path.equals("/api/ehm/v1/system/client-events"))return true;
        if(actor.readOnly()&&!read)return false;
        if(actor.admin())return true;
        boolean export=path.matches(".*/(export|download|backup)(/.*)?");
        if(actor.globalRead()&&read&&!export)return true;
        String relative=path.replaceFirst("^/api/ehm/v1/","");String permission;
        if(relative.startsWith("devices"))permission="ehealth:asset:archive:"+(export?"export":read?"view":"edit");
        else if(relative.startsWith("work-orders"))permission="ehealth:maint:order:"+(export?"export":read?"view":relative.matches("work-orders/[^/]+/(approve|reject)")?"approve":"execute");
        else if(relative.startsWith("alarms"))permission="ehealth:alert:center:"+(export?"export":read?"view":"execute");
        else if(read&&(relative.equals("dashboard/summary")||relative.equals("analytics/overview")||relative.startsWith("workbench")||relative.startsWith("cockpit")))permission="ehealth:wb:cockpit:"+(export?"export":"view");
        else if(relative.startsWith("my-tasks"))permission="ehealth:wb:todo:"+(export?"export":read?"view":"execute");
        else return false;
        return actor.permissions().contains(permission);
    }
}
