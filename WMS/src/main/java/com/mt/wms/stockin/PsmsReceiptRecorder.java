package com.mt.wms.stockin;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** All calls are inside the caller's business transaction. There is deliberately no HTTP here. */
@Component
class PsmsReceiptRecorder {
    private final PsmsInboundRepository repository;
    private final PsmsJson json;
    private final PsmsInboundSettings settings;
    PsmsReceiptRecorder(PsmsInboundRepository repository,PsmsJson json,PsmsInboundSettings settings){this.repository=repository;this.json=json;this.settings=settings;}
    boolean linked(long order){return repository.byOrder(order,false).isPresent();}
    void beforeReceive(long order,StockinModels.ReceiveRequest request){
        var task=repository.byOrder(order,true);
        if(task.isEmpty())return;
        // Do not accept new physical receipts whose callback cannot be delivered by the active binding.
        settings.requireEnabled();settings.requireScope(task.get().companyCode(),task.get().ownerCode());
        if(!task.get().projectId().equals(settings.projectId())||!task.get().regionId().equals(settings.regionId()))
            throw new PsmsInboundException(403,"PSMS_SCOPE_FORBIDDEN","关联入库单不属于当前服务项目与区域，暂停收货并联系维护者");
        if(task.get().status().equals("EXCEPTION"))throw PsmsInboundException.conflict("联动任务处于异常状态，请先协调处理");
        if(request.allowOverReceipt())throw PsmsInboundException.invalid("PSMS_OVER_RECEIPT_FORBIDDEN","调度关联入库单不允许开启超收");
        var lines=repository.lines(task.get().id()).stream().collect(Collectors.toMap(PsmsInboundRepository.TaskLine::orderLineId,Function.identity()));
        for(var input:request.lines()){
            var line=lines.get(input.lineId());
            if(line==null)throw PsmsInboundException.conflict("收货行不属于该调度任务");
            if(input.quantity().stripTrailingZeros().scale()>0||input.quantity().compareTo(line.remainingQty())>0)
                throw PsmsInboundException.invalid("PSMS_QUANTITY_INVALID","调度收货必须为整数且不能超过剩余数量");
            long current=repository.validateGood(task.get().companyId(),task.get().ownerId(),new PsmsInboundModels.TaskLineRequest(line.lineId(),line.goodCode(),line.plannedQty(),line.unit(),line.batchCode(),line.supplierCode()));
            if(current!=line.goodId())throw PsmsInboundException.invalid("PSMS_MATERIAL_CHANGED","物料编码对应关系已改变，请核对后再收货");
        }
        // Transaction-local and bound to one order: old instances sharing this DB cannot skip the new receipt path.
        repository.markReceiptTransaction(order);
    }
    void beforeComplete(long order){
        var task=repository.byOrder(order,true);if(task.isEmpty())return;
        if(!task.get().status().equals("FULLY_RECEIVED"))
            throw PsmsInboundException.conflict("调度关联单尚未完成实收回执，不能确认完成");
        repository.markReceiptTransaction(order);
    }
    void confirmed(PsmsInboundRepository.TaskRow task){append(task,"WAREHOUSE_CONFIRMED",null,List.of());}
    void received(long order,StockinModels.ReceiveRequest request,StockinModels.ReceiveResult result){
        var found=repository.byOrder(order,true);if(found.isEmpty())return;
        var task=found.get();
        Map<Long,BigDecimal> changes=request.lines().stream().collect(Collectors.toMap(StockinModels.ReceiveLineRequest::lineId,StockinModels.ReceiveLineRequest::quantity));
        var lines=repository.lines(task.id()).stream().map(l->new PsmsInboundModels.ReceiptLine(l.lineId(),l.goodCode(),l.unit(),changes.getOrDefault(l.orderLineId(),BigDecimal.ZERO),l.receivedQty(),l.remainingQty())).toList();
        boolean full=lines.stream().allMatch(l->l.remainingQty().signum()==0);
        if(lines.stream().anyMatch(l->l.remainingQty().signum()<0))throw PsmsInboundException.conflict("联动任务的实收数量超过计划");
        append(task,full?"FULLY_RECEIVED":"PARTIALLY_RECEIVED",result.operationCode(),lines);
    }
    private void append(PsmsInboundRepository.TaskRow task,String type,String operation,List<PsmsInboundModels.ReceiptLine> lines){
        var event=new PsmsInboundModels.InboundEvent("1.0","WMS-"+UUID.randomUUID(),task.taskId(),task.version(),task.lastSequence()+1,type,
                OffsetDateTime.now(ZoneOffset.UTC),task.companyCode(),task.ownerCode(),task.warehouseCode(),task.wmsOrderCode(),operation,lines);
        repository.appendEvent(task,event,json.write(event));
    }
}
