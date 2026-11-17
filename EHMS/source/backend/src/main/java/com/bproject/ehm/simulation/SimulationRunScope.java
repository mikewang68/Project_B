package com.bproject.ehm.simulation;

import com.bproject.ehm.platform.security.EhmCentralScope;
import com.bproject.ehm.platform.security.ProjectContext;
import com.bproject.iam.context.ScopeTriple;
import org.springframework.web.context.request.RequestContextHolder;

/** Explicit local worker scope, NOT an IAM user context, token, session, or interactive grant. */
public final class SimulationRunScope {
    public static final String PROJECT="PROJECT_B",REGION="EHMS_DEMO",SUBJECT="ehms-demo-simulator";
    private static final ThreadLocal<ScopeTriple> RUN=new ThreadLocal<>();
    private SimulationRunScope(){}
    public static ScopeTriple current(){
        if(RUN.get()!=null&&(EhmCentralScope.current()!=null||RequestContextHolder.getRequestAttributes()!=null))
            throw new IllegalStateException("DEMO_WORKER_CANNOT_INHERIT_REQUEST_CONTEXT");
        return RUN.get();
    }
    public static AutoCloseable bindDemo(){
        if(EhmCentralScope.current()!=null||RequestContextHolder.getRequestAttributes()!=null)
            throw new IllegalStateException("DEMO_WORKER_CANNOT_INHERIT_REQUEST_CONTEXT");
        if(!PROJECT.equals(ProjectContext.require("UNASSIGNED")))
            throw new IllegalStateException("DEMO_WORKER_PROJECT_MISMATCH");
        var previous=RUN.get();RUN.set(new ScopeTriple(PROJECT,REGION,"EHMS"));
        return ()->{if(previous==null)RUN.remove();else RUN.set(previous);};
    }
}
