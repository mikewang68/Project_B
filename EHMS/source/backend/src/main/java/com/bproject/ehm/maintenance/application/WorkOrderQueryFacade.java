package com.bproject.ehm.maintenance.application;

import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;

public interface WorkOrderQueryFacade {
    PageResult<WorkOrderView> list(PageQuery page);

    MaintenanceMetrics metrics();
}
