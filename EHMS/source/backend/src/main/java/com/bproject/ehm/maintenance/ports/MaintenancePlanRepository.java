package com.bproject.ehm.maintenance.ports;

import com.bproject.ehm.maintenance.domain.model.MaintenancePlan;

import java.util.List;
import java.util.Optional;

public interface MaintenancePlanRepository {
    Optional<MaintenancePlan> findById(String planId);

    List<MaintenancePlan> findByAssetCode(String assetCode);

    MaintenancePlan save(MaintenancePlan plan);
}
