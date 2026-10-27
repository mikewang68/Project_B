package com.mt.wms.dashboard;

import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.TenantContextService;
import com.mt.wms.auth.WmsPrincipal;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class DashboardService {
    private final DashboardRepository repository;
    private final TenantContextService tenants;

    public DashboardService(DashboardRepository repository, TenantContextService tenants) {
        this.repository = repository;
        this.tenants = tenants;
    }

    public DashboardSummary summary(WmsPrincipal principal, HttpSession session) {
        AuthModels.TenantView tenant = tenants.current(principal, session);
        long warehouseId = tenant.currentWarehouse().id();
        long ownerId = tenant.currentOwner().id();
        return new DashboardSummary(
                repository.warehouseCount(principal.companyId()),
                repository.stockInPending(principal.companyId(), warehouseId, ownerId),
                repository.stockOutPending(principal.companyId(), warehouseId, ownerId),
                repository.inventoryAlertCount(principal.companyId(), warehouseId, ownerId),
                OffsetDateTime.now(ZoneOffset.UTC),
                "openGauss 6.0.5");
    }
}
