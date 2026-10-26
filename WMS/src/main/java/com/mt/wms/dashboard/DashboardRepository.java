package com.mt.wms.dashboard;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class DashboardRepository {
    private final JdbcClient jdbc;

    DashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long warehouseCount(long companyId) {
        return jdbc.sql("SELECT count(*) FROM wms_warehouse WHERE company_id=:company AND status='ENABLED'")
                .param("company", companyId).query(Long.class).single();
    }

    long stockInPending(long companyId, long warehouseId, long ownerId) {
        return jdbc.sql("SELECT count(*) FROM stockin_order WHERE company_id=:company AND warehouse_id=:warehouse AND owner_id=:owner AND state IN ('DRAFT','RECEIVING')")
                .param("company", companyId).param("warehouse", warehouseId).param("owner", ownerId)
                .query(Long.class).single();
    }

    long stockOutPending(long companyId, long warehouseId, long ownerId) {
        return jdbc.sql("SELECT count(*) FROM stockout_order WHERE company_id=:company AND warehouse_id=:warehouse AND owner_id=:owner AND state IN ('DRAFT','PROCESSING')")
                .param("company", companyId).param("warehouse", warehouseId).param("owner", ownerId)
                .query(Long.class).single();
    }

    long inventoryAlertCount(long companyId, long warehouseId, long ownerId) {
        return jdbc.sql("""
                SELECT count(*) FROM (
                    SELECT g.id
                      FROM wms_good g
                      LEFT JOIN inv_balance b ON b.good_id=g.id AND b.warehouse_id=:warehouse
                     WHERE g.company_id=:company AND g.owner_id=:owner AND g.status='ENABLED'
                       AND (g.min_quantity>0 OR g.max_quantity>0)
                     GROUP BY g.id,g.min_quantity,g.max_quantity
                    HAVING COALESCE(sum(b.available_qty+b.allocated_qty+b.frozen_qty),0)<g.min_quantity
                       OR (g.max_quantity>0 AND COALESCE(sum(b.available_qty+b.allocated_qty+b.frozen_qty),0)>g.max_quantity)
                ) alerts
                """).param("company", companyId).param("warehouse", warehouseId).param("owner", ownerId)
                .query(Long.class).single();
    }
}
