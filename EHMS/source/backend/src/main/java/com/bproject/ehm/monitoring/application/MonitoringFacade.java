package com.bproject.ehm.monitoring.application;

import com.bproject.ehm.monitoring.domain.model.DeviceSnapshot;
import com.bproject.ehm.monitoring.domain.model.SnapshotMetrics;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MonitoringFacade {
    Optional<DeviceSnapshot> findSnapshot(String deviceCode);

    List<DeviceSnapshot> findSnapshots(Collection<String> deviceCodes);

    DeviceSnapshot saveSnapshot(DeviceSnapshot snapshot);

    SnapshotMetrics metrics();
}
