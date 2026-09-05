package com.bproject.ehm.repository;

import com.bproject.ehm.domain.WorkOrder;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface WorkOrderRepository extends MongoRepository<WorkOrder, String> {
    List<WorkOrder> findAllByOrderByUpdatedAtDesc();
}
