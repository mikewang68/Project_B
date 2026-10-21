package com.bproject.audit.client;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;

/** Backup/restore/training wrappers log STARTED and SUCCESS/FAILED as independent immutable events. */
public class JobAudit {
    private final JdbcLogOutbox queue;
    private final TransactionTemplate tx;
    private final String app,domain,zone;
    public JobAudit(JdbcLogOutbox queue,TransactionTemplate tx,String app,String domain,String zone){this.queue=queue;this.tx=tx;this.app=app;this.domain=domain;this.zone=zone;}
    public <T> T run(String action,String menu,String jobId,Callable<T> task) throws Exception {
        if(!java.util.Set.of("BACKUP","RESTORE","TRAIN","EXECUTE").contains(action))throw new IllegalArgumentException("Invalid job action");
        String pair=UUID.randomUUID().toString();long start=System.nanoTime();
        write(pair+"-START",action,menu,jobId,"STARTED",0);
        try{T result=task.call();write(pair+"-FINISH",action,menu,jobId,"SUCCESS",(System.nanoTime()-start)/1_000_000L);return result;}
        catch(Exception|Error e){write(pair+"-FINISH",action,menu,jobId,"FAILED",(System.nanoTime()-start)/1_000_000L);throw e;}
    }
    private void write(String id,String action,String menu,String jobId,String outcome,long duration){
        var event=new LogEvent(1,"JOB-"+id,Instant.now(),app,domain,zone,"job-runner","SYSTEM",menu,action,jobId,outcome,"","","",duration,"JOB","任务状态，不记录命令参数、文件内容或异常原文");
        // REQUIRES_NEW prevents a failed job transaction from rolling back the failure audit.
        var independent=new TransactionTemplate(tx.getTransactionManager());independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.executeWithoutResult(s->queue.enqueue(event));
    }
}
