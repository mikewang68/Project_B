package com.mt.wms.inventory;

import static com.mt.wms.inventory.InventoryCountModels.*;

import com.mt.wms.auth.TenantContextService;
import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.auth.sso.WmsCentralScope;
import com.mt.wms.inventory.InventoryCountRepository.Scope;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InventoryCountService {
    private static final BigDecimal ZERO=BigDecimal.ZERO;
    private final InventoryCountRepository counts;
    private final InventoryRepository inventory;
    private final TenantContextService tenants;

    InventoryCountService(InventoryCountRepository counts, InventoryRepository inventory, TenantContextService tenants) {
        this.counts=counts;
        this.inventory=inventory;
        this.tenants=tenants;
    }

    List<View> list(State state, Long beforeId, WmsPrincipal user, HttpSession session) {
        return counts.list(scope(user,session),state,beforeId);
    }

    @Transactional
    View submit(Submit input, WmsPrincipal user, HttpSession session) {
        require("ADD");
        Scope scope=scope(user,session);
        var balance=lockBalance(scope,input.balanceId());
        String reason=trim(input.reason());
        var previous=counts.find(scope,input.idempotencyKey());
        if(previous.isPresent()) {
            View old=previous.get();
            if(old.balanceId()!=input.balanceId() || old.balanceVersion()!=input.balanceVersion()
                    || old.actualQty().compareTo(input.actualQuantity())!=0 || old.lossQty().compareTo(input.lossQuantity())!=0
                    || old.missingQty().compareTo(input.missingQuantity())!=0 || !trim(old.reason()).equals(reason))
                throw new IllegalArgumentException("同一请求编号已用于另一份盘点内容");
            return old;
        }
        if(balance.version()!=input.balanceVersion()) throw new IllegalArgumentException("账面库存已变化，请刷新并重新盘点");
        long id=save(scope,balance,input,reason,user.userId());
        return counts.get(scope,id);
    }

    @Transactional
    View review(long id, Review input, WmsPrincipal user, HttpSession session) {
        require("CONFIRM");
        Scope scope=scope(user,session);
        counts.lock(scope,id);
        View count=counts.get(scope,id);
        State desired=input.decision()==Decision.POST?State.POSTED:State.REJECTED;
        if(count.state()==desired) return count;
        if(count.state()!=State.PENDING) throw new IllegalArgumentException("盘点已复核，请查看最新状态");
        String note=trim(input.note());
        if(desired==State.REJECTED) {
            if(note.isEmpty()) throw new IllegalArgumentException("请填写退回重盘的原因");
            counts.finish(scope,id,desired,user.userId(),note,null);
        } else {
            var balance=lockBalance(scope,count.balanceId());
            if(balance.version()!=count.balanceVersion() || !Objects.equals(counts.currentUnit(balance.goodId()),count.unit()))
                throw new IllegalArgumentException("盘点后库存或单位已变化，请退回并重盘");
            post(scope,count,balance,user.userId(),note,"COUNT:"+id);
        }
        return counts.get(scope,id);
    }

    @Transactional
    InventoryModels.OperationResult legacy(InventoryModels.CountRequest input, WmsPrincipal user, HttpSession session) {
        require("ADD");
        require("CONFIRM");
        Scope scope=scope(user,session);
        var balance=lockBalance(scope,input.balanceId());
        if(inventory.processed(scope.companyId(),input.idempotencyKey()))
            throw new IllegalArgumentException("该请求已处理，请勿重复提交");
        String reason=trim(input.remark());
        if(reason.isEmpty()) reason="旧接口直接盘点";
        var submit=new Submit(input.balanceId(),balance.version(),input.actualQuantity(),ZERO,ZERO,reason,input.idempotencyKey());
        long id=save(scope,balance,submit,reason,user.userId());
        View count=counts.get(scope,id);
        String code=post(scope,count,balance,user.userId(),"旧接口确认",input.idempotencyKey());
        return new InventoryModels.OperationResult(code,inventory.view(balance.id(),scope.companyId(),scope.warehouseId(),scope.ownerId()));
    }

    private long save(Scope scope, InventoryRepository.BalanceRow balance, Submit input, String reason, long user) {
        if(counts.pending(scope,balance.id())) throw new IllegalArgumentException("这条库存已有待复核盘点，请先处理原盘点单");
        BigDecimal difference=input.actualQuantity().subtract(balance.total());
        if(difference.signum()!=0 && reason.isEmpty()) throw new IllegalArgumentException("请填写账实差异原因");
        if(input.lossQuantity().add(input.missingQuantity()).compareTo(difference.negate().max(ZERO))>0)
            throw new IllegalArgumentException("损耗和缺失之和不能超过盘亏数量");
        return counts.insert(scope,balance,input,reason,user);
    }

    private String post(Scope scope, View count, InventoryRepository.BalanceRow balance, long user, String note, String key) {
        BigDecimal after=balance.availableQty().add(count.differenceQty());
        if(after.signum()<0) throw new IllegalArgumentException("实盘数量低于已分配量与冻结量之和，请先核对占用");
        if(count.differenceQty().signum()<0 && inventory.serialTracked(balance.id()).compareTo(count.actualQty())>0)
            throw new IllegalArgumentException("实盘数量低于在用标签数量，请先核对标签");
        String code="IC-PD-"+count.id();
        long operation=inventory.operation(scope.companyId(),scope.warehouseId(),scope.ownerId(),code,"COUNT",user,count.reason());
        if(count.differenceQty().signum()!=0) inventory.updateQuantities(balance.id(),after,balance.allocatedQty(),balance.frozenQty());
        inventory.line(operation,balance.goodId(),balance.locationId(),null,balance.id(),count.bookQty(),count.actualQty(),
                count.actualQty(),count.differenceQty(),balance.batchCode(),balance.qualityType(),count.reason());
        inventory.transaction(scope.companyId(),scope.warehouseId(),scope.ownerId(),balance.id(),balance.goodId(),balance.locationId(),
                "COUNT",code,count.bookQty(),count.differenceQty(),count.actualQty(),balance.availableQty(),count.differenceQty(),after,
                balance.frozenQty(),ZERO,balance.frozenQty(),null,key,user,count.reason());
        counts.finish(scope,count.id(),State.POSTED,user,note,operation);
        return code;
    }

    private Scope scope(WmsPrincipal user, HttpSession session) {
        var tenant=tenants.current(user,session);
        return new Scope(user.companyId(),tenant.currentWarehouse().id(),tenant.currentOwner().id());
    }

    private InventoryRepository.BalanceRow lockBalance(Scope scope,long id) {
        return inventory.lock(id,scope.companyId(),scope.warehouseId(),scope.ownerId())
                .orElseThrow(()->new IllegalArgumentException("当前仓库和货主下没有这条库存"));
    }

    private static String trim(String value) { return value==null?"":value.trim(); }

    private static void require(String action) {
        var central=WmsCentralScope.current();
        if(central!=null && (central.readOnly() || !central.hasAction("WMS:inventory",action)))
            throw new AccessDeniedException("没有相应的盘点动作权限");
        WmsCentralScope.concreteWriteScope();
    }
}
