package com.bproject.ehm.platform.audit.application;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;

/** Outer execution journal; the inner business transaction remains authoritative for business success. */
@Aspect @Component @Profile("server") @Order(-200)
public class AuditTaskAspect {
    private final EhmAuditedTasks tasks;
    public AuditTaskAspect(EhmAuditedTasks tasks){this.tasks=tasks;}
    @Around("@annotation(operation)")
    public Object around(ProceedingJoinPoint call,AuditedTask operation) throws Throwable {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs))return call.proceed();
        String id=tasks.start(attrs.getRequest(),operation.action(),operation.menu());
        if(attrs.getResponse()!=null)attrs.getResponse().setHeader("X-EHM-Audit-Task-Id",id);
        Object result;
        try {result=call.proceed();}
        catch(Throwable failure){
            String outcome=failure instanceof ResponseStatusException reject&&(reject.getStatusCode().value()==401||reject.getStatusCode().value()==403)?"DENIED":"FAILED";
            finish(id,outcome,attrs);throw failure;
        }
        int code=result instanceof org.springframework.http.ResponseEntity<?> response?response.getStatusCode().value():attrs.getResponse()==null?200:attrs.getResponse().getStatus();
        finish(id,code==401||code==403?"DENIED":code>=400?"FAILED":"SUCCESS",attrs);
        return result;
    }
    private void finish(String id,String outcome,ServletRequestAttributes attrs) {
        String state;
        try {state=tasks.finish(id,outcome)?outcome.equals("SUCCESS")?"SUCCEEDED":"FAILED":"RECONCILIATION_REQUIRED";}
        catch(RuntimeException unavailable){
            state="RECONCILIATION_REQUIRED";
            org.slf4j.LoggerFactory.getLogger(getClass()).error("AUDIT_TASK_FINALIZATION_PENDING; query the durable task journal");
        }
        // Never replace a committed business response with a claim that its transaction rolled back.
        if(attrs.getResponse()!=null)attrs.getResponse().setHeader("X-EHM-Audit-Task-State",state);
    }
}
