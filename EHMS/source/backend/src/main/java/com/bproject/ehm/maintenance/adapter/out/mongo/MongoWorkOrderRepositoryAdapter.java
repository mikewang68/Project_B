package com.bproject.ehm.maintenance.adapter.out.mongo;

import com.bproject.ehm.maintenance.application.MaintenanceMetrics;
import com.bproject.ehm.maintenance.domain.model.WorkOrder;
import com.bproject.ehm.maintenance.domain.model.WorkOrderStatus;
import com.bproject.ehm.maintenance.ports.WorkOrderRepository;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MongoWorkOrderRepositoryAdapter implements WorkOrderRepository {
    private final MongoWorkOrderSpringRepository repository;
    private final MongoTemplate template;

    public MongoWorkOrderRepositoryAdapter(MongoWorkOrderSpringRepository repository, MongoTemplate template) {
        this.repository = repository;
        this.template = template;
    }

    @Override
    public PageResult<WorkOrder> findAll(PageQuery page) {
        long total = repository.count();
        Query query = new Query().with(PageRequest.of(page.page(), page.size(),
                Sort.by(Sort.Direction.DESC, "updatedAt")));
        List<WorkOrder> content = template.find(query, WorkOrderDocument.class).stream()
                .map(WorkOrderDocument::toDomain).toList();
        return PageResult.of(content, page, total);
    }

    @Override
    public Optional<WorkOrder> findByOrderNo(String orderNo) {
        return repository.findById(orderNo).map(WorkOrderDocument::toDomain);
    }

    @Override
    public WorkOrder save(WorkOrder workOrder) {
        return repository.save(WorkOrderDocument.fromDomain(workOrder)).toDomain();
    }

    @Override
    public long countAll() {
        return repository.count();
    }

    @Override
    public MaintenanceMetrics metrics() {
        long total = repository.count();
        long active = template.count(Query.query(Criteria.where("status")
                .nin(WorkOrderStatus.CLOSED.name(), WorkOrderStatus.CANCELLED.name())), WorkOrderDocument.class);
        long pending = template.count(Query.query(Criteria.where("status")
                .in(WorkOrderStatus.SUBMITTED.name(), WorkOrderStatus.APPROVED.name())), WorkOrderDocument.class);
        return new MaintenanceMetrics(total, active, pending);
    }
}
