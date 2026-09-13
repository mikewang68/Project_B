package com.bproject.ehm.monitoring.application;

import com.bproject.ehm.monitoring.domain.model.DeviceSnapshot;
import com.bproject.ehm.monitoring.domain.model.SnapshotMetrics;
import com.bproject.ehm.monitoring.ports.DeviceSnapshotRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
public class MonitoringApplicationService implements MonitoringFacade {
    private final DeviceSnapshotRepository snapshots;

    public MonitoringApplicationService(DeviceSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public Optional<DeviceSnapshot> findSnapshot(String deviceCode) {
        return snapshots.findByDeviceCode(deviceCode);
    }

    @Override
    public List<DeviceSnapshot> findSnapshots(Collection<String> deviceCodes) {
        return snapshots.findByDeviceCodes(deviceCodes);
    }

    @Override
    public DeviceSnapshot saveSnapshot(DeviceSnapshot snapshot) {
        return snapshots.save(snapshot);
    }

    @Override
    public SnapshotMetrics metrics() {
        return snapshots.metrics();
    }
}
