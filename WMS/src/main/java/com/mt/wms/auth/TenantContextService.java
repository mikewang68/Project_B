package com.mt.wms.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TenantContextService {
    static final String WAREHOUSE_ID = "WMS_WAREHOUSE_ID";
    static final String OWNER_ID = "WMS_OWNER_ID";

    private final AuthRepository repository;

    public TenantContextService(AuthRepository repository) {
        this.repository = repository;
    }

    public AuthModels.TenantView current(WmsPrincipal principal, HttpSession session) {
        boolean all = principal.permissions().contains("tenant:all");
        List<AuthModels.TenantOption> warehouses = repository.findWarehouses(principal.userId(), principal.companyId(), all);
        List<AuthModels.TenantOption> owners = repository.findOwners(principal.userId(), principal.companyId(), all);
        if (warehouses.isEmpty() || owners.isEmpty()) {
            throw new IllegalStateException("当前账号未分配可用仓库或货主");
        }

        AuthModels.TenantOption warehouse = selected(warehouses, session.getAttribute(WAREHOUSE_ID));
        AuthModels.TenantOption owner = selected(owners, session.getAttribute(OWNER_ID));
        session.setAttribute(WAREHOUSE_ID, warehouse.id());
        session.setAttribute(OWNER_ID, owner.id());
        return new AuthModels.TenantView(principal.companyCode(), principal.companyName(), warehouse, owner, warehouses, owners);
    }

    AuthModels.TenantView switchTo(WmsPrincipal principal, HttpSession session, String warehouseCode, String ownerCode) {
        AuthModels.TenantView current = current(principal, session);
        AuthModels.TenantOption warehouse = current.warehouses().stream()
                .filter(item -> item.code().equals(warehouseCode)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("无权访问所选仓库"));
        AuthModels.TenantOption owner = current.owners().stream()
                .filter(item -> item.code().equals(ownerCode)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("无权访问所选货主"));
        session.setAttribute(WAREHOUSE_ID, warehouse.id());
        session.setAttribute(OWNER_ID, owner.id());
        return new AuthModels.TenantView(principal.companyCode(), principal.companyName(), warehouse, owner,
                current.warehouses(), current.owners());
    }

    private AuthModels.TenantOption selected(List<AuthModels.TenantOption> options, Object selectedId) {
        if (selectedId instanceof Long id) {
            return options.stream().filter(item -> item.id().equals(id)).findFirst().orElse(options.get(0));
        }
        return options.get(0);
    }
}
