package com.bproject.ehm.monitoring.ports;

import com.bproject.ehm.monitoring.domain.model.DeviceSnapshot;
import com.bproject.ehm.monitoring.domain.model.SnapshotMetrics;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeviceSnapshotRepository {
    Optional<DeviceSnapshot> findByDeviceCode(String deviceCode);

    List<DeviceSnapshot> findByDeviceCodes(Collection<String> deviceCodes);

    DeviceSnapshot save(DeviceSnapshot snapshot);

    SnapshotMetrics metrics();
}
