package com.bproject.ehm.assistant.application;

import com.bproject.ehm.platform.security.*;
import org.springframework.web.context.request.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Server-only conversation owner; never accept ownership or data scope from the model/client. */
final class AssistantScope {
    private AssistantScope(){}
    static boolean mayRead(String page){
        var c=EhmCentralScope.current();if(c==null)return true;
        if(!c.hasAction("EHMS:"+page,"VIEW"))return false;
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes){
            var p=com.bproject.ehm.platform.audit.adapter.in.web.IamAuditIdentityFilter.actor(attributes.getRequest());
            if(p instanceof EhmSsoSessions.Actor a){
                String nativePermission=switch(page){
                    case "fleet"->"ehealth:asset:archive:view";case "alarm-center"->"ehealth:alert:center:view";
                    case "workorders"->"ehealth:maint:order:view";case "my-tasks"->"ehealth:wb:todo:view";
                    case "shift-handover"->"ehealth:wb:shift:view";case "sensor-cal"->"ehealth:asset:sensor:view";
                    case "config-change"->"ehealth:asset:history:view";case "device-template"->"ehealth:asset:template:view";
                    case "knowledge"->"ehealth:alert:kb:view";default->"";};
                return a.admin()||a.globalRead()||(!nativePermission.isEmpty()&&a.permissions().contains(nativePermission));
            }
            return false;
        }
        return true;
    }
    static String owner(){
        var c=EhmCentralScope.current();var identity=new StringBuilder(ProjectContext.require("UNASSIGNED"));
        if(c!=null){
            identity.append('|').append(c.userId()).append('|').append(c.authorizationVersion()).append('|').append(c.scopeMode())
                .append('|').append(new TreeSet<>(c.activeScope())).append('|').append(c.readOnly());
            c.menuOperations().forEach((menu,actions)->identity.append('|').append(menu).append(new TreeSet<>(actions)));
        }
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes){
            var p=attributes.getRequest().getUserPrincipal();identity.append('|').append(p==null?"anonymous":p.getName());
            if(p instanceof EhmSsoSessions.Actor a)identity.append('|').append(a.iamSid()).append('|').append(new TreeSet<>(a.permissions()));
            else {var session=attributes.getRequest().getSession(false);if(session!=null)identity.append('|').append(session.getId());}
        }
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("Conversation scope unavailable",e);}
    }
}
