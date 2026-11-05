package com.bdemo.sys.log;
import com.bdemo.sys.security.JwtAuthFilter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Install with LogService-atomic.patch. Makes existing command audit join its business transaction. */
@Aspect @Component @ConditionalOnProperty(name="sys.audit.public.enabled",havingValue="true")
public class TransactionalAuditBoundary {
    private final TransactionTemplate tx;private final LogService logs;
    public TransactionalAuditBoundary(TransactionTemplate tx,LogService logs){this.tx=tx;this.logs=logs;}
    @Around("within(com.bdemo.sys..*) && @within(org.springframework.web.bind.annotation.RestController) && execution(public * *(..))")
    public Object around(ProceedingJoinPoint call)throws Throwable{
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs))return call.proceed();
        var request=attrs.getRequest();
        if(request.getAttribute("sysAuditBoundary")!=null)return call.proceed();
        request.setAttribute("sysAuditBoundary",true);
        try{return tx.execute(status->{
            try{var result=call.proceed();
                // Existing command handlers already write their action logs. Add missing read/query audit only.
                if(request.getMethod().equals("GET")&&!request.getRequestURI().endsWith("/export"))logs.record(JwtAuthFilter.currentUser(),"operation","system","query","", "系统查询",request.getMethod(),request.getRequestURI(),request.getRemoteAddr(),"success");
                return result;
            }catch(RuntimeException|Error e){throw e;}catch(Throwable e){throw new IllegalStateException("SYS operation failed",e);}
        });}finally{request.removeAttribute("sysAuditBoundary");}
    }
}
