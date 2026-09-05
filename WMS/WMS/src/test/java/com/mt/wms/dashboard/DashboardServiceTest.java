package com.mt.wms.dashboard;

import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.TenantContextService;
import com.mt.wms.auth.WmsPrincipal;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardServiceTest {
    @Test
    void returnsCurrentTenantMetrics() {
        DashboardRepository repository = mock(DashboardRepository.class);
        TenantContextService tenants = mock(TenantContextService.class);
        HttpSession session = mock(HttpSession.class);
        WmsPrincipal principal = new WmsPrincipal(1L, 2L, "default", "默认公司", "admin", "管理员", List.of("ADMIN"), Set.of(), false);
        AuthModels.TenantOption warehouse = new AuthModels.TenantOption(3L, "WH", "默认仓库");
        AuthModels.TenantOption owner = new AuthModels.TenantOption(4L, "OWNER", "默认货主");
        when(tenants.current(principal, session)).thenReturn(new AuthModels.TenantView("default", "默认公司", warehouse, owner, List.of(warehouse), List.of(owner)));
        when(repository.warehouseCount(2L)).thenReturn(2L);
        when(repository.stockInPending(2L, 3L, 4L)).thenReturn(5L);
        when(repository.stockOutPending(2L, 3L, 4L)).thenReturn(3L);
        when(repository.inventoryAlertCount(2L, 3L, 4L)).thenReturn(1L);

        DashboardSummary summary = new DashboardService(repository, tenants).summary(principal, session);

        assertThat(summary.dataSource()).isEqualTo("openGauss 6.0.5");
        assertThat(summary.warehouseCount()).isEqualTo(2);
        assertThat(summary.stockInPending()).isEqualTo(5);
        assertThat(summary.stockOutPending()).isEqualTo(3);
        assertThat(summary.inventoryAlertCount()).isEqualTo(1);
        assertThat(summary.generatedAt()).isNotNull();
    }
}
