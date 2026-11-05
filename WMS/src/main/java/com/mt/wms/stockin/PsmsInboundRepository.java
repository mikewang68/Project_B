package com.mt.wms.stockin;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PsmsInboundRepository {
    private final JdbcClient jdbc;
    PsmsInboundRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    record Scope(long companyId,long ownerId){}
    record TaskRow(long id,String taskId,int version,String eventId,String requestHash,String requestBody,
            long companyId,long ownerId,String companyCode,String ownerCode,String projectId,String regionId,String planId,String workOrderId,
            OffsetDateTime expectedArrivalAt,String status,Long warehouseId,String warehouseCode,
            Long wmsOrderId,String wmsOrderCode,int lastSequence){}
    record TaskLine(long id,String lineId,long goodId,String goodCode,String unit,BigDecimal plannedQty,
            BigDecimal receivedQty,BigDecimal remainingQty,String batchCode,String supplierCode,Long orderLineId){}
    record Delivery(String state,String lastError){}
    record Outbox(long id,String eventId,String taskId,String payload,int attempts,String leaseToken){}

    Scope lockScope(String company,String owner){
        // Serializes intake/idempotency for this one configured tenant; no stock is touched.
        return jdbc.sql("SELECT c.id AS company_id,o.id AS owner_id FROM auth_company c JOIN wms_owner o ON o.company_id=c.id WHERE c.code=:c AND o.code=:o AND c.status='ENABLED' AND o.status='ENABLED' FOR UPDATE")
                .param("c",company).param("o",owner).query(Scope.class).optional()
                .orElseThrow(()->PsmsInboundException.invalid("PSMS_TENANT_INVALID","公司或货主不存在或已停用"));
    }
    long validateGood(long company,long owner,PsmsInboundModels.TaskLineRequest line){
        record Good(long id,String unit){}
        var good=jdbc.sql("SELECT id,unit FROM wms_good WHERE company_id=:c AND owner_id=:o AND code=:code AND status='ENABLED' FOR SHARE")
                .param("c",company).param("o",owner).param("code",line.goodCode()).query(Good.class).optional()
                .orElseThrow(()->PsmsInboundException.invalid("GOOD_NOT_FOUND","物料不存在或已停用："+line.goodCode()));
        if(!line.unit().equals(good.unit()))throw PsmsInboundException.invalid("UNIT_MISMATCH","物料库存单位不匹配："+line.goodCode());
        if(line.supplierCode()!=null && !line.supplierCode().isBlank()){
            if(jdbc.sql("SELECT id FROM wms_partner WHERE company_id=:c AND code=:code AND status='ENABLED' AND partner_type IN ('SUPPLIER','CLIENT_SUPPLIER') FOR SHARE")
                    .param("c",company).param("code",line.supplierCode()).query(Long.class).optional().isEmpty())
                throw PsmsInboundException.invalid("SUPPLIER_NOT_FOUND","供应商不存在或已停用："+line.supplierCode());
        }
        return good.id();
    }
    private static final String COLUMNS="id,task_id,version,event_id,request_hash,request_body,company_id,owner_id,company_code,owner_code,project_id,region_id,plan_id,work_order_id,expected_arrival_at,status,warehouse_id,warehouse_code,wms_order_id,wms_order_code,last_sequence";
    Optional<TaskRow> byEvent(String event){return jdbc.sql("SELECT "+COLUMNS+" FROM wms_psms_task WHERE source_system='PSMS' AND event_id=:e").param("e",event).query(TaskRow.class).optional();}
    Optional<TaskRow> findByCodes(String task,String company,String owner,String project,String region){return jdbc.sql("SELECT "+COLUMNS+" FROM wms_psms_task WHERE task_id=:t AND company_code=:c AND owner_code=:o AND project_id=:p AND region_id=:r")
            .param("t",task).param("c",company).param("o",owner).param("p",project).param("r",region).query(TaskRow.class).optional();}
    Optional<TaskRow> find(String task,long company,long owner,boolean lock){
        var filter=scopeFilter();
        var query=jdbc.sql("SELECT "+COLUMNS+" FROM wms_psms_task WHERE source_system='PSMS' AND task_id=:t AND company_id=:c AND owner_id=:o"+(filter==null?"":filter.my_info_template())+(lock?" FOR UPDATE":""))
            .param("t",task).param("c",company).param("o",owner);
        if(filter!=null)query=query.params(filter.scope_params());return query.query(TaskRow.class).optional();}
    Optional<TaskRow> byOrder(long order,boolean lock){return jdbc.sql("SELECT "+COLUMNS+" FROM wms_psms_task WHERE wms_order_id=:o"+(lock?" FOR UPDATE":"")).param("o",order).query(TaskRow.class).optional();}
    void markReceiptTransaction(long order){jdbc.sql("SELECT set_config('wms.psms_write',:orderId,true)").param("orderId",Long.toString(order)).query(String.class).single();}
    List<TaskRow> list(long company,long owner,long warehouse){var filter=scopeFilter();var query=jdbc.sql("SELECT "+COLUMNS+" FROM wms_psms_task WHERE company_id=:c AND owner_id=:o AND (warehouse_id IS NULL OR warehouse_id=:w)"+(filter==null?"":filter.my_info_template())+" ORDER BY id DESC LIMIT 200")
            .param("c",company).param("o",owner).param("w",warehouse);if(filter!=null)query=query.params(filter.scope_params());return query.query(TaskRow.class).list();}
    private com.bproject.iam.context.ScopeSql.Filters scopeFilter(){var scope=com.mt.wms.auth.sso.WmsCentralScope.current();return scope==null?null:com.bproject.iam.context.ScopeSql.build(scope.scopeMode(),scope.activeScope());}
    void requireWarehouseScope(long warehouse,long company,String project,String region){
        if(jdbc.sql("SELECT id FROM wms_warehouse WHERE id=:w AND company_id=:c AND project_id=:p AND region_id=:r AND subsystem_code='WMS' AND status='ENABLED' FOR SHARE")
                .param("w",warehouse).param("c",company).param("p",project).param("r",region).query(Long.class).optional().isEmpty())
            throw new PsmsInboundException(403,"PSMS_WAREHOUSE_SCOPE_MISMATCH","所选仓库不属于该调度任务的项目与区域");
    }
    long insert(PsmsInboundModels.TaskRequest r,Scope s,String hash,String body,String project,String region){return jdbc.sql("""
            INSERT INTO wms_psms_task(source_system,task_id,version,event_id,request_hash,request_body,company_id,owner_id,
              company_code,owner_code,project_id,region_id,subsystem_code,plan_id,work_order_id,expected_arrival_at,status)
            VALUES('PSMS',:t,1,:e,:h,:b,:c,:o,:cc,:oc,:project,:region,'WMS',:p,:w,:at,'WAITING_WAREHOUSE') RETURNING id
            """).param("t",r.taskId()).param("e",r.eventId()).param("h",hash).param("b",body).param("c",s.companyId()).param("o",s.ownerId())
            .param("cc",r.companyCode()).param("oc",r.ownerCode()).param("project",project).param("region",region).param("p",r.planId()).param("w",r.workOrderId()).param("at",r.expectedArrivalAt()).query(Long.class).single();}
    void insertLine(long task,PsmsInboundModels.TaskLineRequest l,long good){jdbc.sql("INSERT INTO wms_psms_task_line(task_id,line_id,good_id,good_code,unit,planned_qty,batch_code,supplier_code) VALUES(:t,:l,:g,:code,:u,:q,:b,:s)")
            .param("t",task).param("l",l.lineId()).param("g",good).param("code",l.goodCode()).param("u",l.unit()).param("q",l.plannedQty()).param("b",l.batchCode()).param("s",l.supplierCode()).update();}
    List<TaskLine> lines(long task){return jdbc.sql("""
            SELECT l.id,l.line_id,l.good_id,l.good_code,l.unit,l.planned_qty,
              COALESCE(s.received_qty,0) AS received_qty,l.planned_qty-COALESCE(s.received_qty,0) AS remaining_qty,
              l.batch_code,l.supplier_code,l.order_line_id FROM wms_psms_task_line l
              LEFT JOIN stockin_order_line s ON s.id=l.order_line_id WHERE l.task_id=:t ORDER BY l.id
            """).param("t",task).query(TaskLine.class).list();}
    void linkLine(long id,long orderLine){jdbc.sql("UPDATE wms_psms_task_line SET order_line_id=:o WHERE id=:id AND order_line_id IS NULL").param("id",id).param("o",orderLine).update();}
    void confirm(long id,long warehouse,String warehouseCode,StockinModels.OrderDetail order){
        int changed=jdbc.sql("UPDATE wms_psms_task SET warehouse_id=:w,warehouse_code=:wc,wms_order_id=:o,wms_order_code=:oc,status='WAREHOUSE_CONFIRMED',updated_at=CURRENT_TIMESTAMP WHERE id=:id AND wms_order_id IS NULL")
                .param("w",warehouse).param("wc",warehouseCode).param("o",order.id()).param("oc",order.orderCode()).param("id",id).update();
        if(changed!=1)throw PsmsInboundException.conflict("该任务已经确认，请刷新查看");
    }
    void appendEvent(TaskRow task,PsmsInboundModels.InboundEvent event,String payload){
        int changed=jdbc.sql("UPDATE wms_psms_task SET status=:s,last_sequence=:seq,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND last_sequence=:prev")
                .param("s",event.eventType()).param("seq",event.sequence()).param("prev",task.lastSequence()).param("id",task.id()).update();
        if(changed!=1)throw PsmsInboundException.conflict("任务回执版本变化，请重试原请求");
        jdbc.sql("INSERT INTO wms_psms_outbox(task_id,event_id,sequence,payload,state) VALUES(:t,:e,:seq,:p,'PENDING')")
                .param("t",task.id()).param("e",event.eventId()).param("seq",event.sequence()).param("p",payload).update();
    }
    Optional<Delivery> delivery(long task){return jdbc.sql("SELECT state,last_error FROM wms_psms_outbox WHERE task_id=:t ORDER BY CASE WHEN state='DELIVERED' THEN 1 ELSE 0 END,sequence LIMIT 1").param("t",task).query(Delivery.class).optional();}
    void retryDelivery(long task){jdbc.sql("UPDATE wms_psms_outbox SET state='PENDING',next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE task_id=:t AND state IN ('PENDING','BLOCKED')").param("t",task).update();}
    Optional<Outbox> claim(String lease,String company,String owner,String project,String region){
        record Candidate(long id,String eventId,String taskId,String payload,int attempts){}
        var row=jdbc.sql("""
                SELECT x.id,x.event_id,t.task_id,x.payload,x.attempts FROM wms_psms_outbox x
                  JOIN wms_psms_task t ON t.id=x.task_id
                 WHERE t.company_code=:company AND t.owner_code=:owner AND t.project_id=:project AND t.region_id=:region AND ((x.state='PENDING' AND x.next_attempt_at<=CURRENT_TIMESTAMP)
                      OR (x.state='SENDING' AND x.lease_until<CURRENT_TIMESTAMP))
                  AND NOT EXISTS(SELECT 1 FROM wms_psms_outbox prior WHERE prior.task_id=x.task_id AND prior.sequence<x.sequence AND prior.state<>'DELIVERED')
                 ORDER BY x.id LIMIT 1 FOR UPDATE OF x
                """).param("company",company).param("owner",owner).param("project",project).param("region",region).query(Candidate.class).optional();
        if(row.isEmpty())return Optional.empty();var x=row.get();
        jdbc.sql("UPDATE wms_psms_outbox SET state='SENDING',lease_token=:l,lease_until=CURRENT_TIMESTAMP+INTERVAL '60 seconds',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("l",lease).param("id",x.id()).update();
        return Optional.of(new Outbox(x.id(),x.eventId(),x.taskId(),x.payload(),x.attempts()+1,lease));
    }
    void delivered(Outbox x){jdbc.sql("UPDATE wms_psms_outbox SET state='DELIVERED',delivered_at=CURRENT_TIMESTAMP,last_error=NULL,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND lease_token=:l AND state='SENDING'")
            .param("id",x.id()).param("l",x.leaseToken()).update();}
    void failed(Outbox x,boolean blocked,String reason){
        long delay=Math.min(300,5L*(1L<<Math.min(6,x.attempts()-1)));
        jdbc.sql("UPDATE wms_psms_outbox SET state=:s,last_error=:e,lease_token=NULL,lease_until=NULL,next_attempt_at=:next,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND lease_token=:l AND state='SENDING'")
                .param("s",blocked?"BLOCKED":"PENDING").param("e",reason).param("next",OffsetDateTime.now().plusSeconds(delay)).param("id",x.id()).param("l",x.leaseToken()).update();
    }
}
