package com.bproject.ehm.maintenance.ports;

import com.bproject.ehm.maintenance.application.MaintenanceMetrics;
import com.bproject.ehm.maintenance.domain.model.WorkOrder;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;

import java.util.Optional;

public interface WorkOrderRepository {
    PageResult<WorkOrder> findAll(PageQuery page);

    Optional<WorkOrder> findByOrderNo(String orderNo);

    WorkOrder save(WorkOrder workOrder);

    long countAll();

    MaintenanceMetrics metrics();
}
