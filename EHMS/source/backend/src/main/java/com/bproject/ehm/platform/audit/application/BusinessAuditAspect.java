package com.bproject.ehm.platform.audit.application;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Wrap core commands/queries before the HTTP response and persist audit in the SAME transaction. */
@Aspect @Component @Profile("server")
public class BusinessAuditAspect {
    private final EhmAuditRelay relay;
    private final TransactionTemplate tx;
    public BusinessAuditAspect(EhmAuditRelay relay, TransactionTemplate tx) { this.relay=relay; this.tx=tx; }
    @Around("within(com.bproject.ehm..adapter.in.web..*) && execution(public * *(..)) && !within(com.bproject.ehm.platform..*) && !within(com.bproject.ehm.assistant..*)")
    public Object around(ProceedingJoinPoint call) throws Throwable {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return call.proceed();
        HttpServletRequest request=attrs.getRequest();
        if (!java.util.Set.of("GET","POST","PUT","PATCH","DELETE").contains(request.getMethod())
                || request.getAttribute("businessAuditActive")!=null || !request.getRequestURI().startsWith("/api/ehm/v1/")) return call.proceed();
        request.setAttribute("businessAuditActive",true); long started=System.nanoTime();
        try {
            Object result=tx.execute(status->{
                try {
                    Object value=call.proceed();
                    // Explicit HTTP rejection must not be recorded as a committed business success.
                    int code = value instanceof org.springframework.http.ResponseEntity<?> response
                            ? response.getStatusCode().value() : attrs.getResponse() == null ? 200 : attrs.getResponse().getStatus();
                    if (code >= 400) { status.setRollbackOnly(); return value; }
                    AuditRequestMetadata.createdObject(request,value);
                    relay.record(request,"SUCCESS",request.getMethod().equals("GET")?"REQUEST":"BUSINESS",(System.nanoTime()-started)/1_000_000);
                    return value;
                }
                catch (RuntimeException|Error e) { throw e; }
                catch (Throwable e) { throw new IllegalStateException("Business operation failed",e); }
            });
            int code = result instanceof org.springframework.http.ResponseEntity<?> response
                    ? response.getStatusCode().value() : attrs.getResponse() == null ? 200 : attrs.getResponse().getStatus();
            if (code < 400) request.setAttribute(EhmAuditRelay.BUSINESS_RECORDED,true);
            return result;
        } finally { request.removeAttribute("businessAuditActive"); }
    }
}
