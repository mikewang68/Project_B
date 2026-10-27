package com.mt.wms.stockout;

import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.TenantContextService;
import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.masterdata.BusinessSequenceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
class StockoutService {
    private static final BigDecimal ZERO=BigDecimal.ZERO;
    private static final Set<String> TYPES=Set.of("SALE","PRODUCE","NORMAL","RETURN","COOP","MATERIAL_PICK","FIX","SCRAP","BORROW","TRANSFER","CONSIGN","CUSTOM");
    private static final Set<String> SOURCES=Set.of("ERP","CUSTOM","IMPORT");
    private static final Set<String> QUALITIES=Set.of("ZP","CC","DJ","ZT","JS","XS");
    private final StockoutRepository repository;private final TenantContextService tenants;private final BusinessSequenceService sequences;
    StockoutService(StockoutRepository repository,TenantContextService tenants,BusinessSequenceService sequences){this.repository=repository;this.tenants=tenants;this.sequences=sequences;}

    List<StockoutModels.OrderSummary> list(WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return repository.orders(s.c,s.w,s.o);}
    StockoutModels.OrderDetail detail(long id,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return detail(id,s);}
    List<StockoutModels.AllocationView> allocations(long id,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);repository.order(id,s.c,s.w,s.o);return repository.allocations(id,s.c,s.w,s.o);}
    List<StockoutModels.EventView> events(long id,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);repository.order(id,s.c,s.w,s.o);return repository.events(id,s.c,s.w,s.o);}
    List<StockoutModels.PackageView> packages(long id,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);repository.order(id,s.c,s.w,s.o);return packages(id);}
    List<StockoutModels.WaveView> waves(WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return repository.waves(s.c,s.w,s.o).stream().map(this::waveView).toList();}
    StockoutModels.WaveView wave(long id,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return waveView(repository.wave(id,s.c,s.w,s.o));}

    @Transactional
    StockoutModels.OrderDetail create(StockoutModels.CreateRequest request,WmsPrincipal p,HttpSession session){
        Scope s=scope(p,session);if(repository.createProcessed(s.c,request.idempotencyKey()))throw new IllegalArgumentException("该出库单请求已处理，请勿重复提交");
        String type=type(request.outboundType()),source=source(request.source()),external=optional(request.externalOrderCode());
        if(external!=null&&repository.externalExists(s.c,s.w,s.o,external))throw new IllegalArgumentException("外部出库单号已存在");
        Long partner=optional(request.partnerCode())==null?null:repository.partnerId(s.c,request.partnerCode().trim());
        StockoutRepository.TargetScope target=null;if(type.equals("TRANSFER")){if(optional(request.targetWarehouseCode())==null||optional(request.targetOwnerCode())==null)throw new IllegalArgumentException("调拨出库必须选择目标仓库和货主");target=repository.target(s.c,request.targetWarehouseCode().trim(),request.targetOwnerCode().trim());}
        record Prepared(StockoutModels.LineRequest request,StockoutRepository.GoodRow good,BigDecimal price){}
        List<Prepared> prepared=request.lines().stream().map(line->{StockoutRepository.GoodRow good=repository.good(s.c,s.o,line.goodCode().trim());BigDecimal price=line.unitPrice()==null?good.price():line.unitPrice();return new Prepared(line,good,price==null?ZERO:price);}).toList();
        BigDecimal amount=prepared.stream().map(x->x.request.plannedQty().multiply(x.price)).reduce(ZERO,BigDecimal::add);String code=sequences.next("O",p,session);
        long id=repository.createOrder(s.c,s.w,s.o,code,external,optional(request.relatedOrderCode()),type,source,partner,request.plannedDate(),optional(request.receiverName()),optional(request.receiverPhone()),optional(request.receiverAddress()),optional(request.carrierCode()),optional(request.trackingCode()),amount,target==null?null:target.warehouseId(),target==null?null:target.ownerId(),p.userId(),request.remark(),request.idempotencyKey());
        int no=1;for(Prepared x:prepared){StockoutModels.LineRequest line=x.request;repository.createLine(id,no++,x.good.id(),line.plannedQty(),dimension(line.supplierCode()),quality(line.qualityType()),dimension(line.batchCode()),x.price,line.remark());}
        if(target!=null)createTransferInbound(id,code,target,prepared,p,session,request);
        return detail(id,s);
    }

    private void createTransferInbound(long outboundId,String outboundCode,StockoutRepository.TargetScope target,List<?> rawPrepared,WmsPrincipal p,HttpSession session,StockoutModels.CreateRequest request){
        String inboundCode=sequences.next("R",p,session);long inbound=repository.createInbound(p.companyId(),target.warehouseId(),target.ownerId(),inboundCode,outboundCode,"TRANSFER",p.userId(),"调拨入库："+outboundCode,request.idempotencyKey()+":TRANSFER_IN");Long stage=repository.stageLocation(p.companyId(),target.warehouseId());int no=1;
        for(StockoutModels.LineRequest line:request.lines()){StockoutRepository.GoodRow sourceGood=repository.good(p.companyId(),scope(p,session).o,line.goodCode().trim());long targetGood=repository.targetGood(p.companyId(),target.ownerId(),sourceGood.code());repository.createInboundLine(inbound,no++,targetGood,line.plannedQty(),stage,dimension(line.supplierCode()),quality(line.qualityType()),dimension(line.batchCode()),line.unitPrice()==null?ZERO:line.unitPrice(),line.remark());}
        repository.transferIn(outboundId,inbound);
    }

    @Transactional
    StockoutModels.OperationResult allocate(long id,StockoutModels.AllocateRequest request,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return allocateInternal(id,request.allowPartial(),request.idempotencyKey(),request.remark(),p,session,s,true);}
    private StockoutModels.OperationResult allocateInternal(long id,boolean allowPartial,String key,String remark,WmsPrincipal p,HttpSession session,Scope s,boolean mark){
        StockoutRepository.OrderLock order=repository.lockOrder(id,s.c,s.w,s.o);open(order);
        if(mark)mark(s.c,id,"ALLOCATE",key);if(order.allocationState().equals("FULL"))throw new IllegalArgumentException("出库单已全部分配");
        String operation=sequences.next("OA",p,session);long op=repository.operation(s.c,s.w,s.o,operation,p.userId(),remark,key+":INV");BigDecimal total=ZERO;
        for(StockoutRepository.LineRow line:repository.lineRows(id)){
            BigDecimal need=line.plannedQty().subtract(line.allocatedQty());if(need.signum()<=0)continue;List<StockoutRepository.BalanceRow> balances=repository.lockBalances(s.c,s.w,s.o,line);BigDecimal available=balances.stream().map(StockoutRepository.BalanceRow::availableQty).reduce(ZERO,BigDecimal::add);
            if(!allowPartial&&available.compareTo(need)<0)throw new IllegalArgumentException("货品库存不足，不允许部分分配");BigDecimal left=need;
            for(StockoutRepository.BalanceRow b:balances){if(left.signum()<=0)break;BigDecimal q=b.availableQty().min(left);repository.allocateBalance(b.id(),q);long allocation=repository.allocation(s.c,s.w,s.o,id,line.id(),b,q);repository.addAllocated(line.id(),q);repository.operationLine(op,b,b.total(),q,q,remark);repository.transaction(s.c,s.w,s.o,b,operation,ZERO,q.negate(),key+":A:"+allocation,p.userId(),remark);repository.event(s.c,s.w,s.o,id,line.id(),allocation,"ALLOCATE",operation,q,b.locationId(),p.userId(),remark);total=total.add(q);left=left.subtract(q);}
        }
        if(total.signum()==0)throw new IllegalArgumentException("没有可分配库存");refresh(id,false);return new StockoutModels.OperationResult(operation,detail(id,s),total);
    }

    @Transactional
    StockoutModels.OperationResult pick(long id,StockoutModels.PickRequest request,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return pickInternal(id,request,key(request.idempotencyKey()),p,session,s,true);}
    private StockoutModels.OperationResult pickInternal(long id,StockoutModels.PickRequest request,String key,WmsPrincipal p,HttpSession session,Scope s,boolean mark){
        StockoutRepository.OrderLock order=repository.lockOrder(id,s.c,s.w,s.o);open(order);if(order.allocationState().equals("NO"))throw new IllegalArgumentException("出库单还未分配");if(mark)mark(s.c,id,"PICK",key);String operation=sequences.next("OP",p,session);BigDecimal total=ZERO;Set<String> requestSerials=new HashSet<>();Set<Long> requestLines=new HashSet<>();
        for(StockoutModels.PickLineRequest input:request.lines()){
            long lineId=input.lineId()!=null?input.lineId():repository.lineIdByBarcode(id,required(input.barcode(),"请提供明细或条码"));if(!requestLines.add(lineId))throw new IllegalArgumentException("同一拣货请求不能重复提交同一明细");StockoutRepository.LineRow line=repository.lockLine(lineId,id);List<StockoutRepository.AllocationRow> allocations=repository.lockAllocations(id,lineId);BigDecimal can=allocations.stream().map(a->a.allocatedQty().subtract(a.pickedQty())).reduce(ZERO,BigDecimal::add);if(input.quantity().compareTo(can)>0)throw new IllegalArgumentException("拣货数量超过已分配未拣数量");
            List<String> serials=input.serialCodes()==null?List.of():input.serialCodes().stream().map(String::trim).filter(x->!x.isEmpty()).toList();if(BigDecimal.valueOf(serials.size()).compareTo(input.quantity())>0)throw new IllegalArgumentException("RFID/唯一标签数量不能超过拣货数量");
            BigDecimal left=input.quantity();for(StockoutRepository.AllocationRow a:allocations){if(left.signum()<=0)break;BigDecimal q=a.allocatedQty().subtract(a.pickedQty()).min(left);if(q.signum()>0){repository.addPicked(lineId,a.id(),q);repository.event(s.c,s.w,s.o,id,lineId,a.id(),"PICK",operation,q,a.locationId(),p.userId(),input.remark());left=left.subtract(q);}}
            for(String serial:serials){if(!requestSerials.add(serial))throw new IllegalArgumentException("同一请求中存在重复标签");StockoutRepository.SerialRow sr=repository.lockSerial(s.c,s.w,s.o,serial);if(!sr.state().equals("ACTIVE")||!sr.goodId().equals(line.goodId()))throw new IllegalArgumentException("标签状态或货品不匹配："+serial);StockoutRepository.AllocationRow match=allocations.stream().filter(a->a.balanceId().equals(sr.balanceId())).findFirst().orElseThrow(()->new IllegalArgumentException("标签所在库存未分配给该出库单："+serial));repository.pickSerial(id,lineId,match.id(),sr.id());}
            total=total.add(input.quantity());
        }
        refresh(id,false);return new StockoutModels.OperationResult(operation,detail(id,s),total);
    }

    @Transactional
    StockoutModels.OperationResult pickAll(long id,StockoutModels.ActionRequest request,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);List<StockoutModels.PickLineRequest> lines=repository.lineRows(id).stream().filter(x->x.allocatedQty().subtract(x.pickedQty()).signum()>0).map(x->new StockoutModels.PickLineRequest(x.id(),null,x.allocatedQty().subtract(x.pickedQty()),List.of(),request.remark())).toList();if(lines.isEmpty())throw new IllegalArgumentException("没有待拣数量");return pickInternal(id,new StockoutModels.PickRequest(request.idempotencyKey(),lines),request.idempotencyKey(),p,session,s,true);}

    @Transactional
    StockoutModels.PackageView createPackage(long id,StockoutModels.PackageRequest request,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);StockoutRepository.OrderLock order=repository.lockOrder(id,s.c,s.w,s.o);open(order);mark(s.c,id,"PACK",request.idempotencyKey());String code=sequences.next("PK",p,session);long pkg=repository.createPackage(s.c,s.w,s.o,id,code,optional(request.carrierCode()),optional(request.trackingCode()),p.userId(),request.remark());Set<Long> ids=new HashSet<>();
        for(StockoutModels.PackageLineRequest input:request.lines()){if(!ids.add(input.lineId()))throw new IllegalArgumentException("包裹内不能重复添加同一明细");StockoutRepository.LineRow line=repository.lockLine(input.lineId(),id);BigDecimal can=line.pickedQty().subtract(repository.packedQty(id,line.id()));if(input.quantity().compareTo(can)>0)throw new IllegalArgumentException("装箱数量超过已拣未装箱数量");repository.packageLine(pkg,line.id(),input.quantity());repository.event(s.c,s.w,s.o,id,line.id(),null,"PACK",code,input.quantity(),null,p.userId(),request.remark());}
        return packages(id).stream().filter(x->x.id().equals(pkg)).findFirst().orElseThrow();}

    @Transactional
    StockoutModels.OperationResult ship(long id,StockoutModels.ShipRequest request,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return shipInternal(id,request,p,session,s,true);}
    private StockoutModels.OperationResult shipInternal(long id,StockoutModels.ShipRequest request,WmsPrincipal p,HttpSession session,Scope s,boolean mark){
        StockoutRepository.OrderLock order=repository.lockOrder(id,s.c,s.w,s.o);open(order);if(order.pickState().equals("NO"))throw new IllegalArgumentException("出库单还未拣货");if(mark)mark(s.c,id,"SHIP",request.idempotencyKey());List<ShipLine> shipLines;
        if(request.packageId()!=null){StockoutRepository.PackageLock pkg=repository.lockPackage(request.packageId(),id);if(!pkg.state().equals("OPEN"))throw new IllegalArgumentException("包裹已发运或已取消");shipLines=repository.packageLines(pkg.id()).stream().map(x->new ShipLine(x.orderLineId(),x.quantity())).toList();}else shipLines=repository.lineRows(id).stream().filter(x->x.pickedQty().subtract(x.shippedQty()).signum()>0).map(x->new ShipLine(x.id(),x.pickedQty().subtract(x.shippedQty()))).toList();
        if(shipLines.isEmpty())throw new IllegalArgumentException("没有待发运数量");String operation=sequences.next("OS",p,session);long op=repository.operation(s.c,s.w,s.o,operation,p.userId(),request.remark(),request.idempotencyKey()+":INV");BigDecimal total=ZERO;
        for(ShipLine input:shipLines){StockoutRepository.LineRow line=repository.lockLine(input.lineId,id);BigDecimal can=line.pickedQty().subtract(line.shippedQty());if(input.quantity.compareTo(can)>0)throw new IllegalArgumentException("发运数量超过已拣未发数量");BigDecimal left=input.quantity;for(StockoutRepository.AllocationRow a:repository.lockAllocations(id,line.id())){if(left.signum()<=0)break;BigDecimal q=a.pickedQty().subtract(a.shippedQty()).min(left);if(q.signum()<=0)continue;StockoutRepository.BalanceRow b=repository.lockBalance(a.balanceId());repository.shipBalance(b.id(),q);repository.addShipped(line.id(),a.id(),q);repository.operationLine(op,b,b.total(),q,q,request.remark());repository.transaction(s.c,s.w,s.o,b,operation,q.negate(),ZERO,request.idempotencyKey()+":S:"+a.id(),p.userId(),request.remark());repository.event(s.c,s.w,s.o,id,line.id(),a.id(),"SHIP",operation,q,a.locationId(),p.userId(),request.remark());left=left.subtract(q);total=total.add(q);}if(left.signum()>0)throw new IllegalArgumentException("发运分配明细不足");}
        if(request.packageId()!=null)repository.shipPackage(request.packageId());refresh(id,false);return new StockoutModels.OperationResult(operation,detail(id,s),total);
    }

    @Transactional
    StockoutModels.OrderDetail cancelAllocation(long id,StockoutModels.ActionRequest request,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);unallocate(id,request.idempotencyKey(),request.remark(),p,session,s,true,false);return detail(id,s);}
    private void unallocate(long id,String key,String remark,WmsPrincipal p,HttpSession session,Scope s,boolean mark,boolean allowShipped){StockoutRepository.OrderLock order=repository.lockOrder(id,s.c,s.w,s.o);open(order);if(mark)mark(s.c,id,"UNALLOCATE",key);List<StockoutRepository.AllocationRow> allocations=repository.lockOrderAllocations(id);if(allocations.isEmpty())throw new IllegalArgumentException("没有可取消的分配");if(!allowShipped&&allocations.stream().anyMatch(x->x.shippedQty().signum()>0))throw new IllegalArgumentException("出库单已发运，不能取消分配");String operation=sequences.next("OU",p,session);long op=repository.operation(s.c,s.w,s.o,operation,p.userId(),remark,key+":INV");Set<Long> lines=new HashSet<>();
        for(StockoutRepository.AllocationRow a:allocations){BigDecimal q=a.allocatedQty().subtract(a.shippedQty());if(q.signum()>0){StockoutRepository.BalanceRow b=repository.lockBalance(a.balanceId());repository.unallocateBalance(b.id(),q);repository.operationLine(op,b,b.total(),q,q,remark);repository.transaction(s.c,s.w,s.o,b,operation,ZERO,q,key+":U:"+a.id(),p.userId(),remark);repository.event(s.c,s.w,s.o,id,a.orderLineId(),a.id(),"UNALLOCATE",operation,q,a.locationId(),p.userId(),remark);}repository.cancelAllocation(a.id());lines.add(a.orderLineId());}
        for(Long line:lines)repository.resetLine(line);if(!allowShipped)repository.restoreSerials(id);repository.cancelOpenPackages(id);refresh(id,false);}

    @Transactional
    StockoutModels.OrderDetail cancel(long id,StockoutModels.ActionRequest request,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);StockoutRepository.OrderLock order=repository.lockOrder(id,s.c,s.w,s.o);if(order.state().equals("COMPLETED"))throw new IllegalArgumentException("已完成出库单不能取消");if(order.state().equals("CANCELLED"))return detail(id,s);mark(s.c,id,"CANCEL",request.idempotencyKey());StockoutRepository.Progress progress=repository.progress(id);if(progress.shipped().signum()>0)throw new IllegalArgumentException("已发运出库单不能取消");if(progress.allocated().signum()>0)unallocate(id,request.idempotencyKey()+":UNALLOCATE",request.remark(),p,session,s,false,false);repository.cancelOrder(id,request.remark());repository.cancelInbound(order.transferInOrderId());repository.event(s.c,s.w,s.o,id,null,null,"CANCEL",null,ZERO,null,p.userId(),request.remark());return detail(id,s);}

    @Transactional
    StockoutModels.OrderDetail complete(long id,StockoutModels.ActionRequest request,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);StockoutRepository.OrderLock order=repository.lockOrder(id,s.c,s.w,s.o);if(order.state().equals("CANCELLED"))throw new IllegalArgumentException("已取消出库单不能完成");if(order.state().equals("COMPLETED"))return detail(id,s);mark(s.c,id,"COMPLETE",request.idempotencyKey());StockoutRepository.Progress progress=repository.progress(id);if(progress.picked().subtract(progress.shipped()).signum()>0)shipInternal(id,new StockoutModels.ShipRequest(null,request.idempotencyKey()+":SHIP",request.remark()),p,session,s,true);progress=repository.progress(id);if(progress.allocated().subtract(progress.shipped()).signum()>0)unallocate(id,request.idempotencyKey()+":UNALLOCATE",request.remark(),p,session,s,false,true);refresh(id,true);repository.event(s.c,s.w,s.o,id,null,null,"COMPLETE",null,repository.progress(id).shipped(),null,p.userId(),request.remark());return detail(id,s);}

    @Transactional
    String reverse(long id,StockoutModels.ActionRequest request,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);StockoutRepository.OrderLock order=repository.lockOrder(id,s.c,s.w,s.o);if(!order.state().equals("COMPLETED"))throw new IllegalArgumentException("只有已完成出库单可生成退库单");mark(s.c,id,"REVERSE",request.idempotencyKey());String code=sequences.next("R",p,session);long inbound=repository.createInbound(s.c,s.w,s.o,code,order.orderCode(),"RETURN",p.userId(),request.remark(),request.idempotencyKey()+":INBOUND");Long stage=repository.stageLocation(s.c,s.w);int no=1;for(StockoutRepository.LineRow line:repository.lineRows(id)){if(line.shippedQty().signum()>0)repository.createInboundLine(inbound,no++,line.goodId(),line.shippedQty(),stage,line.supplierCode(),line.qualityType(),line.batchCode(),line.unitPrice(),request.remark());}repository.event(s.c,s.w,s.o,id,null,null,"REVERSE",code,repository.progress(id).shipped(),null,p.userId(),request.remark());return code;}

    @Transactional
    StockoutModels.WaveView createWave(StockoutModels.WaveCreateRequest request,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);if(repository.requestProcessed(s.c,request.idempotencyKey()))throw new IllegalArgumentException("该波次请求已处理");Set<Long> ids=new HashSet<>(request.orderIds());if(ids.size()<2)throw new IllegalArgumentException("波次至少包含两张不同出库单");for(Long id:ids){StockoutRepository.OrderLock order=repository.lockOrder(id,s.c,s.w,s.o);if(!order.state().equals("DRAFT")||!order.allocationState().equals("NO"))throw new IllegalArgumentException("只能将未分配草稿单加入波次");}String code=sequences.next("W",p,session);long wave=repository.createWave(s.c,s.w,s.o,code,p.userId(),request.idempotencyKey(),request.remark());for(Long id:ids)repository.waveOrder(wave,id);repository.request(s.c,null,"WAVE_CREATE",request.idempotencyKey());return wave(wave,p,session);}

    @Transactional
    StockoutModels.WaveView allocateWave(long waveId,StockoutModels.WaveActionRequest request,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);StockoutRepository.WaveRow wave=repository.wave(waveId,s.c,s.w,s.o);if(!wave.state().equals("DRAFT"))throw new IllegalArgumentException("波次已分配或已拣货");for(Long id:repository.waveOrderIds(waveId))allocateInternal(id,request.allowPartial(),request.idempotencyKey()+":"+id,request.remark(),p,session,s,true);repository.waveState(waveId,"ALLOCATED");return wave(waveId,p,session);}
    @Transactional
    StockoutModels.WaveView pickWave(long waveId,StockoutModels.ActionRequest request,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);StockoutRepository.WaveRow wave=repository.wave(waveId,s.c,s.w,s.o);if(!wave.state().equals("ALLOCATED"))throw new IllegalArgumentException("波次还未分配或已拣货");for(Long id:repository.waveOrderIds(waveId)){List<StockoutModels.PickLineRequest> lines=repository.lineRows(id).stream().filter(x->x.allocatedQty().subtract(x.pickedQty()).signum()>0).map(x->new StockoutModels.PickLineRequest(x.id(),null,x.allocatedQty().subtract(x.pickedQty()),List.of(),request.remark())).toList();if(!lines.isEmpty())pickInternal(id,new StockoutModels.PickRequest(request.idempotencyKey()+":"+id,lines),request.idempotencyKey()+":"+id,p,session,s,true);}repository.waveState(waveId,"PICKED");return wave(waveId,p,session);}

    private void refresh(long id,boolean forceComplete){StockoutRepository.Progress x=repository.progress(id);String allocation=progressState(x.allocated(),x.planned()),pick=progressState(x.picked(),x.planned()),ship=progressState(x.shipped(),x.planned());boolean completed=forceComplete||ship.equals("FULL");String state=completed?"COMPLETED":(x.allocated().signum()>0||x.picked().signum()>0||x.shipped().signum()>0)?"PROCESSING":"DRAFT";repository.states(id,allocation,pick,ship,state,completed);}
    private String progressState(BigDecimal value,BigDecimal planned){if(value.signum()==0)return "NO";return value.compareTo(planned)>=0?"FULL":"PARTIAL";}
    private void mark(long c,Long order,String type,String key){if(repository.requestProcessed(c,key))throw new IllegalArgumentException("该作业请求已处理，请勿重复提交");repository.request(c,order,type,key);}
    private void open(StockoutRepository.OrderLock order){if(Set.of("COMPLETED","CANCELLED").contains(order.state()))throw new IllegalArgumentException("出库单已完成或已取消，不能继续作业");}
    private StockoutModels.OrderDetail detail(long id,Scope s){StockoutRepository.OrderHeader h=repository.order(id,s.c,s.w,s.o);List<StockoutModels.LineView> lines=repository.lines(id);StockoutRepository.PartnerRow p=repository.partner(h.partnerId());BigDecimal planned=lines.stream().map(StockoutModels.LineView::plannedQty).reduce(ZERO,BigDecimal::add),allocated=lines.stream().map(StockoutModels.LineView::allocatedQty).reduce(ZERO,BigDecimal::add),picked=lines.stream().map(StockoutModels.LineView::pickedQty).reduce(ZERO,BigDecimal::add),shipped=lines.stream().map(StockoutModels.LineView::shippedQty).reduce(ZERO,BigDecimal::add);return new StockoutModels.OrderDetail(h.id(),h.orderCode(),h.externalOrderCode(),h.relatedOrderCode(),h.outboundType(),h.source(),h.state(),h.allocationState(),h.pickState(),h.shipState(),p.code(),p.name(),h.plannedDate(),h.receiverName(),h.receiverPhone(),h.receiverAddress(),h.carrierCode(),h.trackingCode(),planned,allocated,picked,shipped,h.totalAmount(),h.targetWarehouseCode(),h.targetOwnerCode(),h.transferInOrderCode(),h.remark(),h.createdAt(),h.completedAt(),lines);}
    private List<StockoutModels.PackageView> packages(long id){return repository.packageRows(id).stream().map(x->new StockoutModels.PackageView(x.id(),x.packageCode(),x.carrierCode(),x.trackingCode(),x.state(),x.shippedAt(),x.remark(),x.createdAt(),repository.packageLines(x.id()))).toList();}
    private StockoutModels.WaveView waveView(StockoutRepository.WaveRow x){List<String> codes=repository.waveOrderCodes(x.id());return new StockoutModels.WaveView(x.id(),x.waveCode(),x.state(),codes.size(),x.remark(),x.createdAt(),codes);}
    private Scope scope(WmsPrincipal p,HttpSession session){AuthModels.TenantView t=tenants.current(p,session);return new Scope(p.companyId(),t.currentWarehouse().id(),t.currentOwner().id());}
    private String type(String value){String v=text(value).toUpperCase();if(!TYPES.contains(v))throw new IllegalArgumentException("出库类型不合法");return v;}
    private String source(String value){String v=text(value).toUpperCase();if(v.isEmpty())v="CUSTOM";if(!SOURCES.contains(v))throw new IllegalArgumentException("出库来源不合法");return v;}
    private String quality(String value){String v=text(value).toUpperCase();if(v.isEmpty())v="ZP";if(!QUALITIES.contains(v))throw new IllegalArgumentException("质量类型不合法");return v;}
    private String dimension(String value){String v=text(value);return v.isEmpty()?"-":v;}
    private String optional(String value){String v=text(value);return v.isEmpty()?null:v;}
    private String required(String value,String msg){String v=text(value);if(v.isEmpty())throw new IllegalArgumentException(msg);return v;}
    private String key(String value){return required(value,"幂等键不能为空");}
    private String text(String value){return value==null?"":value.trim();}
    private record Scope(long c,long w,long o){}
    private record ShipLine(Long lineId,BigDecimal quantity){}
}
