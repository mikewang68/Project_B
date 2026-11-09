package com.mt.wms.inventory;

import static com.mt.wms.inventory.InventoryCountModels.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mt.wms.fixture.LocalWarehouseDbFixture;
import com.mt.wms.auth.sso.WmsCentralScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

class InventoryCountDatabaseTest extends LocalWarehouseDbFixture {
    @Autowired InventoryCountService counts;
    @Autowired InventoryService inventory;

    @Test void lossAndMissingAreReviewedWithoutOverwritingAllocationsOrDoublePosting() {
        var counter=counter();
        var reviewer=reviewer();
        assertEquals("wms_app",jdbc.sql("SELECT current_user").query(String.class).single());
        bind("WMS:inventory","FIX_REGION",false,"VIEW","ADD");
        var row=inventory.balances(counter,session).stream().filter(b->b.goodCode().equals("T-COUNT")).findFirst().orElseThrow();
        var input=new Submit(row.id(),row.version(),qty("9.750"),qty("0.500"),qty("0.250"),"Fixture loss and missing","count-01");
        var saved=counts.submit(input,counter,session);
        assertEquals(State.PENDING,saved.state());
        assertEquals(qty("10.500"),saved.bookQty());
        assertEquals(qty("-0.750"),saved.differenceQty());
        assertEquals(qty("0.500"),saved.lossQty());
        assertEquals(qty("0.250"),saved.missingQty());
        assertEquals(qty("10.000"),available("T-COUNT"));
        assertEquals(saved.id(),counts.submit(input,counter,session).id());
        assertThrows(IllegalArgumentException.class,()->counts.submit(new Submit(row.id(),row.version(),
                qty("8"),qty("0"),qty("0"),"Conflicting replay","count-01"),counter,session));
        var post=new Review(Decision.POST,"Recount checked");
        assertFalse(WmsCentralScope.route(WmsCentralScope.current(),"POST","/api/v1/inventory/counts"));
        assertThrows(AccessDeniedException.class,()->counts.review(saved.id(),post,counter,session));
        bind("WMS:inventory","FIX_REGION",true,"VIEW","CONFIRM");
        assertThrows(AccessDeniedException.class,()->counts.review(saved.id(),post,reviewer,session));
        bind("WMS:inventory","FIX_OTHER_REGION",false,"VIEW","CONFIRM");
        assertTrue(counts.list(null,null,reviewer,session).isEmpty());
        assertThrows(IllegalArgumentException.class,()->counts.review(saved.id(),post,reviewer,session));

        bind("WMS:inventory","FIX_REGION",false,"VIEW","CONFIRM");
        var posted=counts.review(saved.id(),post,reviewer,session);
        assertEquals(State.POSTED,posted.state());
        assertEquals("Fixture Reviewer",posted.reviewedBy());
        assertEquals(qty("9.250"),available("T-COUNT"));
        assertEquals(posted.operationCode(),counts.review(saved.id(),post,reviewer,session).operationCode());
        assertEquals(1,jdbc.sql("SELECT count(*) FROM inv_transaction WHERE operation_code=:op")
                .param("op",posted.operationCode()).query(Integer.class).single());
        var balance=inventory.balances(reviewer,session).stream().filter(b->b.id().equals(row.id())).findFirst().orElseThrow();
        assertEquals(qty("9.750"),balance.totalQty());
        assertEquals(qty("0.250"),balance.allocatedQty());
        assertEquals(qty("0.250"),balance.frozenQty());
    }

    @Test void staleCountsCanBeRejectedAndDoNotOverwriteConcurrentStockMovements() {
        var counter=counter();
        bind("WMS:inventory","FIX_REGION",false,"VIEW","ADD","CONFIRM");
        var row=inventory.balances(counter,session).stream().filter(b->b.goodCode().equals("T-STALE")).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->counts.submit(new Submit(row.id(),row.version(),
                qty("9"),qty("0.8"),qty("0.5"),"Too much","count-invalid"),counter,session));
        var saved=counts.submit(new Submit(row.id(),row.version(),qty("9.5"),qty("0"),qty("0.5"),"Before movement","count-02"),counter,session);
        jdbc.sql("UPDATE inv_balance SET available_qty=available_qty+1,version=version+1 WHERE id=:id").param("id",row.id()).update();
        assertThrows(IllegalArgumentException.class,()->counts.review(saved.id(),new Review(Decision.POST,""),reviewer(),session));
        var rejected=counts.review(saved.id(),new Review(Decision.REJECT,"Inventory changed"),reviewer(),session);
        assertEquals(State.REJECTED,rejected.state());
        assertNull(rejected.operationCode());
        assertEquals(qty("11.000"),available("T-STALE"));
    }
}
