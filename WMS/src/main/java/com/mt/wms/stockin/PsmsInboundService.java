package com.mt.wms.stockin;

import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.sso.WmsCentralScope;
import com.mt.wms.auth.TenantContextService;
import com.mt.wms.auth.WmsPrincipal;
import jakarta.servlet.http.HttpSession;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PsmsInboundService {
    private final PsmsInboundRepository repository;
    private final PsmsInboundSettings settings;
    private final PsmsJson json;
    private final TenantContextService tenants;
    private final StockinService stockin;
    private final PsmsReceiptRecorder recorder;
    PsmsInboundService(PsmsInboundRepository repository,PsmsInboundSettings settings,PsmsJson json,
            TenantContextService tenants,StockinService stockin,PsmsReceiptRecorder recorder){
        this.repository=repository;this.settings=settings;this.json=json;this.tenants=tenants;this.stockin=stockin;this.recorder=recorder;
    }
    @Transactional
    PsmsInboundModels.Accepted accept(PsmsInboundModels.TaskRequest original){
        settings.requireEnabled();settings.requireScope(original.companyCode(),original.ownerCode());
        var request=normalize(original);var scope=repository.lockScope(request.companyCode(),request.ownerCode());
        String body=json.write(request),hash=PsmsJson.hashText(body);
        var event=repository.byEvent(request.eventId());
        if(event.isPresent())return repeated(event.get(),request,hash);
        var previous=repository.find(request.taskId(),scope.companyId(),scope.ownerId(),false);
        if(previous.isPresent())return repeated(previous.get(),request,hash);
        var ids=new java.util.ArrayList<Long>();
        for(var line:request.lines())ids.add(repository.validateGood(scope.companyId(),scope.ownerId(),line));
        long task=repository.insert(request,scope,hash,body,settings.projectId(),settings.regionId());
        for(int i=0;i<request.lines().size();i++)repository.insertLine(task,request.lines().get(i),ids.get(i));
        return new PsmsInboundModels.Accepted(request.taskId(),1,"WAITING_WAREHOUSE");
    }
    private PsmsInboundModels.Accepted repeated(PsmsInboundRepository.TaskRow old,PsmsInboundModels.TaskRequest r,String hash){
        if(!old.companyCode().equals(r.companyCode())||!old.ownerCode().equals(r.ownerCode())||!old.requestHash().equals(hash)||!old.projectId().equals(settings.projectId())||!old.regionId().equals(settings.regionId()))
            throw PsmsInboundException.conflict("该任务号或事件号已用于不同请求；首次派发后不允许直接覆盖");
        return new PsmsInboundModels.Accepted(r.taskId(),1,"WAITING_WAREHOUSE");
    }
    static PsmsInboundModels.TaskRequest normalize(PsmsInboundModels.TaskRequest r){
        var ids=new HashSet<String>();
        var lines=r.lines().stream().map(l->{
            if(!ids.add(l.lineId()))throw new PsmsInboundException(400,"DUPLICATE_LINE","物料行号不能重复");
            if(l.plannedQty().stripTrailingZeros().scale()>0)throw PsmsInboundException.invalid("INTEGER_QUANTITY_REQUIRED","首期仅支持正整数库存单位");
            return new PsmsInboundModels.TaskLineRequest(l.lineId(),l.goodCode(),l.plannedQty().stripTrailingZeros(),l.unit(),blank(l.batchCode()),blank(l.supplierCode()));
        }).toList();
        return new PsmsInboundModels.TaskRequest(r.schemaVersion(),r.eventId(),r.taskId(),r.version(),r.planId(),r.workOrderId(),r.companyCode(),r.ownerCode(),r.expectedArrivalAt().withOffsetSameInstant(ZoneOffset.UTC),lines);
    }
    private static String blank(String value){return value==null||value.isBlank()?null:value;}
    @Transactional(readOnly=true)
    PsmsInboundModels.TaskView machineGet(String task){
        settings.requireEnabled();
        // A read never takes the intake serialization lock.
        return view(repository.findByCodes(task,settings.companyCode(),settings.ownerCode(),settings.projectId(),settings.regionId()).orElseThrow(PsmsInboundService::missing));
    }
    @Transactional(readOnly=true)
    List<PsmsInboundModels.TaskView> list(WmsPrincipal principal,HttpSession session){
        var context=tenants.current(principal,session);
        return repository.list(principal.companyId(),context.currentOwner().id(),context.currentWarehouse().id()).stream().map(this::view).toList();
    }
    @Transactional(readOnly=true)
    PsmsInboundModels.TaskView get(String task,WmsPrincipal principal,HttpSession session){
        var context=tenants.current(principal,session);
        return view(scoped(task,principal,context,false));
    }
    @Transactional
    PsmsInboundModels.TaskView retryDelivery(String task,WmsPrincipal principal,HttpSession session){
        settings.requireEnabled();requireAction("EDIT");var context=tenants.current(principal,session);
        var row=scoped(task,principal,context,true);requireServiceBinding(row);
        repository.retryDelivery(row.id());return view(row);
    }
    @Transactional
    PsmsInboundModels.TaskView confirm(String task,PsmsInboundModels.ConfirmRequest request,WmsPrincipal principal,HttpSession session){
        settings.requireEnabled();requireAction("ADD");var context=tenants.current(principal,session);
        if(!context.currentWarehouse().code().equals(request.warehouseCode())||!context.currentOwner().code().equals(request.ownerCode()))
            throw new PsmsInboundException(403,"PSMS_CONTEXT_CHANGED","请先切换到有权限的仓库和货主，再确认此任务");
        var row=scoped(task,principal,context,true);
        requireServiceBinding(row);
        repository.requireWarehouseScope(context.currentWarehouse().id(),row.companyId(),row.projectId(),row.regionId());
        if(row.wmsOrderId()!=null)return view(row);
        if(!row.status().equals("WAITING_WAREHOUSE"))throw PsmsInboundException.conflict("任务当前状态不允许建单");
        repository.lockScope(row.companyCode(),row.ownerCode());
        var lines=repository.lines(row.id());
        for(var l:lines){
            long current=repository.validateGood(row.companyId(),row.ownerId(),new PsmsInboundModels.TaskLineRequest(l.lineId(),l.goodCode(),l.plannedQty(),l.unit(),l.batchCode(),l.supplierCode()));
            if(current!=l.goodId())throw PsmsInboundException.invalid("PSMS_MATERIAL_CHANGED","接单后物料编码对应关系已改变，请与调度方核对");
        }
        var requestLines=lines.stream().map(l->new StockinModels.LineRequest(l.goodCode(),l.plannedQty(),null,l.supplierCode(),"ZP",null,null,l.batchCode(),null,null)).toList();
        String stable="PSMS-"+PsmsJson.hashText(row.companyCode()+"\n"+row.ownerCode()+"\n"+row.taskId()).substring(0,48);
        var create=new StockinModels.CreateRequest("NORMAL","CUSTOM",stable,row.workOrderId(),null,row.expectedArrivalAt().atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate(),"PSMS调度到货："+row.taskId(),stable,requestLines);
        // Capture scope once; another tab switching the shared session cannot change this transaction's warehouse.
        var order=stockin.createForPsms(create,principal,context.currentWarehouse().id(),context.currentOwner().id());
        if(order.lines().size()!=lines.size())throw new IllegalStateException("入库明细映射数量不一致");
        for(int i=0;i<lines.size();i++)repository.linkLine(lines.get(i).id(),order.lines().get(i).id());
        repository.confirm(row.id(),context.currentWarehouse().id(),context.currentWarehouse().code(),order);
        recorder.confirmed(repository.byOrder(order.id(),true).orElseThrow());
        return view(repository.byOrder(order.id(),false).orElseThrow());
    }
    private PsmsInboundRepository.TaskRow scoped(String task,WmsPrincipal principal,AuthModels.TenantView context,boolean lock){
        var row=repository.find(task,principal.companyId(),context.currentOwner().id(),lock).orElseThrow(PsmsInboundService::missing);
        if(row.warehouseId()!=null&&!row.warehouseId().equals(context.currentWarehouse().id()))throw missing();
        return row;
    }
    private void requireServiceBinding(PsmsInboundRepository.TaskRow row){
        settings.requireScope(row.companyCode(),row.ownerCode());
        if(!row.projectId().equals(settings.projectId())||!row.regionId().equals(settings.regionId()))
            throw new PsmsInboundException(403,"PSMS_SCOPE_FORBIDDEN","任务不在当前服务项目、区域授权范围内");
    }
    private static void requireAction(String action){
        var scope=WmsCentralScope.current();
        if(scope!=null&&(scope.readOnly()||!scope.hasAction("WMS:stockin",action)))
            throw new org.springframework.security.access.AccessDeniedException("当前中央权限不允许该入库动作");
    }
    private static PsmsInboundException missing(){return new PsmsInboundException(404,"PSMS_TASK_NOT_FOUND","当前业务范围内未找到该到货任务");}
    private PsmsInboundModels.TaskView view(PsmsInboundRepository.TaskRow row){
        var delivery=repository.delivery(row.id()).orElse(new PsmsInboundRepository.Delivery("NONE",null));
        var lines=repository.lines(row.id()).stream().map(l->new PsmsInboundModels.LineView(l.lineId(),l.goodCode(),l.unit(),l.plannedQty(),l.receivedQty(),l.remainingQty(),l.batchCode(),l.supplierCode())).toList();
        return new PsmsInboundModels.TaskView(row.taskId(),row.version(),row.planId(),row.workOrderId(),row.companyCode(),row.ownerCode(),row.expectedArrivalAt(),row.status(),row.warehouseCode(),row.wmsOrderCode(),row.wmsOrderId(),row.lastSequence(),lines,delivery.state(),delivery.lastError());
    }
}
