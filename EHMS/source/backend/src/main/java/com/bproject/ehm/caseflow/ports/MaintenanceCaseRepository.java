package com.bproject.ehm.caseflow.ports;

import com.bproject.ehm.caseflow.domain.model.MaintenanceCase;

import java.util.Optional;

public interface MaintenanceCaseRepository {
    Optional<MaintenanceCase> findByAlarmNo(String alarmNo);

    Optional<MaintenanceCase> findByWorkOrderNo(String workOrderNo);

    MaintenanceCase save(MaintenanceCase maintenanceCase);
}
