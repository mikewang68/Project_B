package com.bproject.ehm.maintenance.application;

import com.bproject.ehm.asset.application.AssetQueryFacade;
import com.bproject.ehm.asset.application.DeviceView;
import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.maintenance.domain.model.WorkOrder;
import com.bproject.ehm.maintenance.domain.model.WorkOrderStatus;
import com.bproject.ehm.maintenance.ports.WorkOrderIdGenerator;
import com.bproject.ehm.maintenance.ports.WorkOrderRepository;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import com.bproject.ehm.shared.error.ValidationException;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class WorkOrderApplicationService implements WorkOrderQueryFacade {
    private final WorkOrderRepository workOrders;
    private final WorkOrderIdGenerator idGenerator;
    private final AssetQueryFacade assets;
    private final Clock clock;

    @Autowired
    public WorkOrderApplicationService(WorkOrderRepository workOrders, WorkOrderIdGenerator idGenerator,
                                       AssetQueryFacade assets) {
        this(workOrders, idGenerator, assets, Clock.systemUTC());
    }

    WorkOrderApplicationService(WorkOrderRepository workOrders, WorkOrderIdGenerator idGenerator,
                                AssetQueryFacade assets, Clock clock) {
        this.workOrders = workOrders;
        this.idGenerator = idGenerator;
        this.assets = assets;
        this.clock = clock;
    }

    @Override
    public PageResult<WorkOrderView> list(PageQuery page) {
        return workOrders.findAll(page).map(WorkOrderView::from);
    }

    public WorkOrderView create(WorkOrderCommand command) {
        try {
            String code = Asset.normalizeCode(command.deviceCode());
            DeviceView device = assets.get(code);
            Instant now = clock.instant();
            WorkOrder order = WorkOrder.create(idGenerator.next(now), code, device.name(), command.title(),
                    command.priority(), command.assignee(), command.source(), command.description(),
                    command.plannedWindow(), command.operator(), now);
            return WorkOrderView.from(workOrders.save(order));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public WorkOrderView transition(String orderNo, String targetStatus, String operator, String reason) {
        WorkOrder current = workOrders.findByOrderNo(orderNo)
                .orElseThrow(() -> new ResourceNotFoundException("未找到工单：" + orderNo));
        WorkOrder changed = current.transitionTo(WorkOrderStatus.from(targetStatus), operator, reason, clock.instant());
        return WorkOrderView.from(workOrders.save(changed));
    }

    @Override
    public MaintenanceMetrics metrics() {
        return workOrders.metrics();
    }
}
