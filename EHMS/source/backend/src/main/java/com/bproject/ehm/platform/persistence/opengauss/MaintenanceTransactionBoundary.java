package com.bproject.ehm.platform.persistence.opengauss;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Same-database maintenance consistency; no distributed transaction or PLC side effect. */
@Aspect @Component @Profile("server")
public class MaintenanceTransactionBoundary {
    private final TransactionTemplate transactions;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private OpenGaussJsonStore store;
    public MaintenanceTransactionBoundary(PlatformTransactionManager manager){transactions=new TransactionTemplate(manager);}
    @Around("execution(public * com.bproject.ehm.maintenance.application.MaintenanceProgramApplicationService.*(..)) || execution(public * com.bproject.ehm.caseflow.application.CaseFlowApplicationService.*(..)) || execution(public * com.bproject.ehm.health.application.HealthAssessmentApplicationService.*(..))")
    public Object atomic(ProceedingJoinPoint point) {
        return transactions.execute(status->{try{
            if(store!=null&&point.getArgs().length>0&&point.getArgs()[0] instanceof String id){
                String method=point.getSignature().getName();
                String type=switch(method){case "review","createWorkOrder"->"health-assessment";
                    case "generateTask"->"maintenance-plan";case "startTask","submitTask"->"inspection-task";
                    case "createDefectWorkOrder"->"defect";default->null;};
                if(type!=null)store.lockAggregate(type,id);
            }
            return point.proceed();}
            catch(RuntimeException|Error error){throw error;}
            catch(Throwable error){throw new IllegalStateException("维护流程未完成，相关业务写入已回滚",error);}});
    }
}
