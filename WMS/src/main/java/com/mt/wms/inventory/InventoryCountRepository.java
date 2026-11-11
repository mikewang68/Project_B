package com.mt.wms.inventory;

import static com.mt.wms.inventory.InventoryCountModels.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InventoryCountRepository {
    record Scope(long companyId, long warehouseId, long ownerId) {}
    private static final String FILTER = "c.company_id=:company AND c.warehouse_id=:warehouse AND c.owner_id=:owner";
    private static final String VIEWS = """
        SELECT c.id,c.balance_id,c.balance_version,c.good_code,c.good_name,c.location_code,
               c.batch_code,c.quality_type,c.supplier_code,c.lpn,c.unit,c.book_qty,c.actual_qty,
               c.difference_qty,c.loss_qty,c.missing_qty,c.reason,c.state,
               author.display_name AS created_by,c.created_at,reviewer.display_name AS reviewed_by,
               c.reviewed_at,c.review_note,o.operation_code
        FROM public.inv_count c JOIN public.auth_user author ON author.id=c.created_by
        LEFT JOIN public.auth_user reviewer ON reviewer.id=c.reviewed_by
        LEFT JOIN public.inv_operation o ON o.id=c.operation_id
        WHERE """ + " " + FILTER;

    private final JdbcClient jdbc;

    InventoryCountRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    List<View> list(Scope scope, State state, Long beforeId) {
        var query=scoped(VIEWS + (state==null?"":" AND c.state=:state")
                + (beforeId==null?"":" AND c.id<:before") + " ORDER BY c.id DESC LIMIT 100",scope);
        if(state!=null) query.param("state",state.name());
        if(beforeId!=null) query.param("before",beforeId);
        return query.query(View.class).list();
    }

    Optional<View> find(Scope scope, String key) {
        return scoped(VIEWS+" AND c.request_key=:key",scope).param("key",key).query(View.class).optional();
    }

    boolean pending(Scope scope, long balanceId) {
        return scoped("SELECT c.id FROM public.inv_count c WHERE "+FILTER+" AND c.balance_id=:id AND c.state='PENDING'",scope)
                .param("id",balanceId).query(Long.class).optional().isPresent();
    }

    View get(Scope scope, long id) {
        return scoped(VIEWS+" AND c.id=:id",scope).param("id",id).query(View.class).optional()
                .orElseThrow(()->new IllegalArgumentException("当前仓库和货主下没有这张盘点单"));
    }

    void lock(Scope scope, long id) {
        scoped("SELECT c.id FROM public.inv_count c WHERE "+FILTER+" AND c.id=:id FOR UPDATE",scope)
                .param("id",id).query(Long.class).optional()
                .orElseThrow(()->new IllegalArgumentException("当前仓库和货主下没有这张盘点单"));
    }

    long insert(Scope scope, InventoryRepository.BalanceRow balance, Submit input, String reason, long actor) {
        return scoped("""
            INSERT INTO public.inv_count(company_id,warehouse_id,owner_id,balance_id,balance_version,
                good_code,good_name,location_code,batch_code,quality_type,supplier_code,lpn,unit,
                book_qty,actual_qty,difference_qty,loss_qty,missing_qty,reason,request_key,created_by)
            SELECT :company,:warehouse,:owner,:balance,:version,g.code,g.name,l.code,:batch,:quality,:supplier,
                   :lpn,g.unit,:book,:actual,:difference,:loss,:missing,:reason,:key,:actor
            FROM public.wms_good g JOIN public.wms_location l ON l.id=:location
            WHERE g.id=:good
            RETURNING id
            """,scope).param("balance",balance.id()).param("version",balance.version())
                .param("location",balance.locationId()).param("good",balance.goodId())
                .param("batch",balance.batchCode()).param("quality",balance.qualityType())
                .param("supplier",balance.supplierCode()).param("lpn",balance.lpn())
                .param("book",balance.total()).param("actual",input.actualQuantity())
                .param("difference",input.actualQuantity().subtract(balance.total())).param("loss",input.lossQuantity())
                .param("missing",input.missingQuantity()).param("reason",reason.isEmpty()?null:reason)
                .param("key",input.idempotencyKey()).param("actor",actor).query(Long.class).single();
    }

    String currentUnit(long goodId) {
        return jdbc.sql("SELECT unit FROM public.wms_good WHERE id=:id FOR SHARE")
                .param("id",goodId).query(String.class).optional().orElse(null);
    }

    void finish(Scope scope, long id, State state, long actor, String note, Long operationId) {
        int changed=scoped("""
            UPDATE public.inv_count c SET state=:state,reviewed_by=:actor,reviewed_at=CURRENT_TIMESTAMP,
                review_note=:note,operation_id=:operation WHERE """+" "+FILTER+" AND c.id=:id AND c.state='PENDING'",scope)
                .param("state",state.name()).param("actor",actor).param("note",note)
                .param("operation",operationId).param("id",id).update();
        if(changed!=1) throw new IllegalStateException("盘点单已复核");
    }

    private JdbcClient.StatementSpec scoped(String sql, Scope scope) {
        return jdbc.sql(sql).param("company",scope.companyId()).param("warehouse",scope.warehouseId()).param("owner",scope.ownerId());
    }
}
