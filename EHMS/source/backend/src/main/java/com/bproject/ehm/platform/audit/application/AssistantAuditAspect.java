package com.bproject.ehm.platform.audit.application;

import com.bproject.ehm.assistant.application.AssistantApplicationService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.Map;

/** No database transaction is held open across a model HTTP call. Prompts and answers are never logged. */
@Aspect @Component @Profile("server")
public class AssistantAuditAspect {
    private final EhmAuditRelay relay;
    public AssistantAuditAspect(EhmAuditRelay relay) { this.relay = relay; }
    @Around("within(com.bproject.ehm.assistant.adapter.in.web.AssistantController) && execution(public * *(..))")
    public Object around(ProceedingJoinPoint call) throws Throwable {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return call.proceed();
        long start = System.nanoTime();
        Object result = call.proceed(); // Unhandled failures/denials are captured by AuditTrailFilter.
        String outcome = "SUCCESS";
        if (result instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("success"))) outcome = "FAILED";
        if (result instanceof AssistantApplicationService.AssistantResponse answer && "local-fallback".equals(answer.mode())) outcome = "FAILED";
        relay.record(attrs.getRequest(), outcome, "REQUEST", (System.nanoTime()-start)/1_000_000L);
        attrs.getRequest().setAttribute(EhmAuditRelay.BUSINESS_RECORDED, true);
        return result;
    }
}
