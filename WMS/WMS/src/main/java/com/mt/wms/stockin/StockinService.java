package com.mt.wms.stockin;

import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.TenantContextService;
import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.masterdata.BusinessSequenceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
class StockinService {
    private static final BigDecimal ZERO=BigDecimal.ZERO;
    private static final Set<String> TYPES=Set.of("PURCHASE","RETURN","TRANSFER","CONSIGN","CUSTOM","PRODUCE","MATERIAL_RETURN","FIX","BORROW_RETURN","NORMAL","COOP_RETURN","PRODUCE_RETURN");
    private static final Set<String> SOURCES=Set.of("ERP","CUSTOM","IMPORT","QUICK");
    private static final Set<String> QUALITIES=Set.of("ZP","CC","DJ","ZT","JS","XS");
    private final StockinRepository repository;private final TenantContextService tenants;private final BusinessSequenceService sequences;
    StockinService(StockinRepository repository,TenantContextService tenants,BusinessSequenceService sequences){this.repository=repository;this.tenants=tenants;this.sequences=sequences;}

    List<StockinModels.OrderSummary> list(WmsPrincipal principal,HttpSession session){Scope s=scope(principal,session);return repository.orders(s.c,s.w,s.o);}
    StockinModels.OrderDetail detail(long id,WmsPrincipal principal,HttpSession session){Scope s=scope(principal,session);return detail(id,s);}
    List<StockinModels.ReceiptView> receipts(long id,WmsPrincipal principal,HttpSession session){Scope s=scope(principal,session);repository.order(id,s.c,s.w,s.o);return repository.receipts(id,s.c,s.w,s.o);}
    List<StockinModels.LocationSuggestion> locationSuggestions(String goodCode,WmsPrincipal principal,HttpSession session){Scope s=scope(principal,session);StockinRepository.GoodRow good=repository.good(s.c,s.o,goodCode.trim());return repository.locationSuggestions(s.c,s.w,s.o,good.id());}

    @Transactional
    StockinModels.OrderDetail create(StockinModels.CreateRequest request,WmsPrincipal principal,HttpSession session){Scope s=scope(principal,session);return createInternal(request,principal,session,s);}

    private StockinModels.OrderDetail createInternal(StockinModels.CreateRequest request,WmsPrincipal principal,HttpSession session,Scope s){
        if(repository.createProcessed(s.c,request.idempotencyKey()))throw new IllegalArgumentException("该入库单请求已处理，请勿重复提交");
        String type=type(request.inboundType()),source=source(request.source());
        String external=optional(request.externalOrderCode());
        if(external!=null&&repository.externalExists(s.c,s.w,s.o,external))throw new IllegalArgumentException("外部入库单号已存在");
        Long partner=optional(request.partnerCode())==null?null:repository.partnerId(s.c,request.partnerCode().trim());
        record Prepared(StockinModels.LineRequest request,StockinRepository.GoodRow good,Long location,BigDecimal price){}
        List<Prepared> prepared=request.lines().stream().map(line->{
            StockinRepository.GoodRow good=repository.good(s.c,s.o,line.goodCode().trim());
            Long location=optional(line.preferredLocationCode())==null?null:repository.locationId(s.c,s.w,line.preferredLocationCode().trim());
            BigDecimal price=line.unitPrice()==null?good.price():line.unitPrice();return new Prepared(line,good,location,price==null?ZERO:price);
        }).toList();
        BigDecimal amount=prepared.stream().map(x->x.request.plannedQty().multiply(x.price)).reduce(ZERO,BigDecimal::add);
        String orderCode=sequences.next("R",principal,session);
        long orderId=repository.createOrder(s.c,s.w,s.o,orderCode,external,optional(request.relatedOrderCode()),type,source,partner,request.plannedDate(),amount,principal.userId(),request.remark(),request.idempotencyKey());
        int no=1;for(Prepared x:prepared){StockinModels.LineRequest line=x.request;repository.createLine(orderId,no++,x.good.id(),line.plannedQty(),x.location,dimension(line.supplierCode()),quality(line.qualityType()),line.productDate(),line.expireDate(),dimension(line.batchCode()),x.price,line.remark());}
        return detail(orderId,s);
    }

    @Transactional
    StockinModels.ReceiveResult receive(long orderId,StockinModels.ReceiveRequest request,WmsPrincipal principal,HttpSession session){
        Scope s=scope(principal,session);StockinRepository.OrderLock order=repository.lockOrder(orderId,s.c,s.w,s.o);
        if(Set.of("COMPLETED","CANCELLED").contains(order.state()))throw new IllegalArgumentException("入库单已完成或已取消，不能继续收货");
        if(repository.operationProcessed(s.c,request.idempotencyKey()))throw new IllegalArgumentException("该收货请求已处理，请勿重复提交");
        Set<Long> lineIds=new HashSet<>();for(StockinModels.ReceiveLineRequest line:request.lines())if(!lineIds.add(line.lineId()))throw new IllegalArgumentException("同一收货请求不能重复提交同一明细");
        String operationCode=sequences.next("IR",principal,session);long operationId=repository.operation(s.c,s.w,s.o,operationCode,principal.userId(),"入库收货 "+order.orderCode(),request.idempotencyKey());
        BigDecimal receivedTotal=ZERO;int receiptCount=0;Set<String> requestSerials=new HashSet<>();
        for(StockinModels.ReceiveLineRequest input:request.lines()){
            StockinRepository.LineLock line=repository.lockLine(input.lineId(),orderId);BigDecimal remaining=line.plannedQty().subtract(line.receivedQty());
            if(!request.allowOverReceipt()&&input.quantity().compareTo(remaining)>0)throw new IllegalArgumentException("收货数量超过明细未收数量");
            long location=repository.locationId(s.c,s.w,input.locationCode().trim());String batch=dimension(line.batchCode()),supplier=dimension(line.supplierCode()),quality=quality(line.qualityType()),lpn=dimension(input.lpn());
            StockinRepository.BalanceRow balance=repository.lockBalance(s.c,s.w,s.o,location,line.goodId(),batch,quality,supplier,lpn).orElseGet(()->{long id=repository.createBalance(s.c,s.w,s.o,location,line.goodId(),batch,quality,supplier,line.productDate(),line.expireDate(),lpn);return repository.lockBalance(s.c,s.w,s.o,location,line.goodId(),batch,quality,supplier,lpn).orElseThrow();});
            List<String> serials=input.serialCodes()==null?List.of():input.serialCodes().stream().map(String::trim).filter(x->!x.isEmpty()).toList();
            if(BigDecimal.valueOf(serials.size()).compareTo(input.quantity())>0)throw new IllegalArgumentException("RFID/唯一标签数量不能超过本次收货数量");
            for(String serial:serials)if(!requestSerials.add(serial))throw new IllegalArgumentException("同一收货请求中存在重复标签");
            BigDecimal before=balance.total();repository.addInventory(balance.id(),input.quantity());repository.operationLine(operationId,line.goodId(),location,balance.id(),before,input.quantity(),batch,quality,input.remark());
            String key=request.idempotencyKey()+":L"+line.id();repository.transaction(s.c,s.w,s.o,balance.id(),line.goodId(),location,operationCode,before,input.quantity(),balance.availableQty(),balance.frozenQty(),key,principal.userId(),input.remark());
            repository.receipt(s.c,s.w,s.o,orderId,line.id(),operationCode,balance.id(),line.goodId(),location,input.quantity(),batch,quality,supplier,lpn,serials.size(),principal.userId(),key,input.remark());
            for(String serial:serials)repository.serial(s.c,s.w,s.o,balance.id(),line.goodId(),location,serial,principal.userId());
            repository.addReceived(line.id(),input.quantity());receivedTotal=receivedTotal.add(input.quantity());receiptCount++;
        }
        boolean completed=repository.allReceived(orderId);repository.state(orderId,completed?"COMPLETED":"RECEIVING",completed);
        return new StockinModels.ReceiveResult(operationCode,detail(orderId,s),receiptCount,receivedTotal);
    }

    @Transactional
    StockinModels.ReceiveResult scan(long orderId,StockinModels.ScanRequest request,WmsPrincipal principal,HttpSession session){
        Scope s=scope(principal,session);repository.order(orderId,s.c,s.w,s.o);
        long lineId=repository.lineIdByBarcode(orderId,request.barcode().trim());
        StockinModels.ReceiveLineRequest line=new StockinModels.ReceiveLineRequest(lineId,request.quantity(),request.locationCode(),request.lpn(),request.serialCodes(),request.remark());
        return receive(orderId,new StockinModels.ReceiveRequest(request.idempotencyKey(),request.allowOverReceipt(),List.of(line)),principal,session);
    }

    @Transactional
    StockinModels.OrderDetail complete(long id,StockinModels.ActionRequest request,WmsPrincipal principal,HttpSession session){Scope s=scope(principal,session);StockinRepository.OrderLock order=repository.lockOrder(id,s.c,s.w,s.o);if(order.state().equals("CANCELLED"))throw new IllegalArgumentException("已取消入库单不能完成");if(!order.state().equals("COMPLETED"))repository.state(id,"COMPLETED",true);return detail(id,s);}
    @Transactional
    StockinModels.OrderDetail cancel(long id,StockinModels.ActionRequest request,WmsPrincipal principal,HttpSession session){Scope s=scope(principal,session);StockinRepository.OrderLock order=repository.lockOrder(id,s.c,s.w,s.o);if(order.state().equals("COMPLETED"))throw new IllegalArgumentException("已完成入库单不能取消");if(!order.state().equals("CANCELLED"))repository.cancel(id,request.remark());return detail(id,s);}

    @Transactional
    StockinModels.ReceiveResult quick(StockinModels.QuickRequest request,WmsPrincipal principal,HttpSession session){
        Scope s=scope(principal,session);StockinModels.LineRequest line=new StockinModels.LineRequest(request.goodCode(),request.quantity(),request.locationCode(),request.supplierCode(),request.qualityType(),request.productDate(),request.expireDate(),request.batchCode(),request.unitPrice(),request.remark());
        StockinModels.CreateRequest create=new StockinModels.CreateRequest(type(request.inboundType()),"QUICK",null,null,request.partnerCode(),null,request.remark(),request.idempotencyKey(),List.of(line));
        StockinModels.OrderDetail order=createInternal(create,principal,session,s);StockinModels.ReceiveLineRequest receiveLine=new StockinModels.ReceiveLineRequest(order.lines().get(0).id(),request.quantity(),request.locationCode(),request.lpn(),request.serialCodes(),request.remark());
        return receive(order.id(),new StockinModels.ReceiveRequest(request.idempotencyKey()+":RECEIVE",false,List.of(receiveLine)),principal,session);
    }

    private StockinModels.OrderDetail detail(long id,Scope s){StockinRepository.OrderHeader h=repository.order(id,s.c,s.w,s.o);List<StockinModels.LineView> lines=repository.lines(id);StockinRepository.PartnerRow p=repository.partner(h.partnerId());BigDecimal planned=lines.stream().map(StockinModels.LineView::plannedQty).reduce(ZERO,BigDecimal::add);BigDecimal received=lines.stream().map(StockinModels.LineView::receivedQty).reduce(ZERO,BigDecimal::add);return new StockinModels.OrderDetail(h.id(),h.orderCode(),h.externalOrderCode(),h.relatedOrderCode(),h.inboundType(),h.source(),h.state(),p.code(),p.name(),h.plannedDate(),planned,received,h.totalAmount(),h.remark(),h.createdAt(),h.finishedAt(),lines);}
    private Scope scope(WmsPrincipal p,HttpSession session){AuthModels.TenantView t=tenants.current(p,session);return new Scope(p.companyId(),t.currentWarehouse().id(),t.currentOwner().id());}
    private String type(String value){String v=text(value).toUpperCase();if(!TYPES.contains(v))throw new IllegalArgumentException("入库类型不合法");return v;}
    private String source(String value){String v=text(value).toUpperCase();if(v.isEmpty())v="CUSTOM";if(!SOURCES.contains(v))throw new IllegalArgumentException("入库来源不合法");return v;}
    private String quality(String value){String v=text(value).toUpperCase();if(v.isEmpty())v="ZP";if(!QUALITIES.contains(v))throw new IllegalArgumentException("质量类型不合法");return v;}
    private String dimension(String value){String v=text(value);return v.isEmpty()?"-":v;}
    private String optional(String value){String v=text(value);return v.isEmpty()?null:v;}
    private String text(String value){return value==null?"":value.trim();}
    private record Scope(long c,long w,long o){}
}
