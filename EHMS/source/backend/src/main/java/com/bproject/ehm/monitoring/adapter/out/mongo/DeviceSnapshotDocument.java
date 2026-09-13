package com.bproject.ehm.monitoring.adapter.out.mongo;

import com.bproject.ehm.monitoring.domain.model.DeviceSnapshot;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("device_snapshots")
public class DeviceSnapshotDocument {
    @Id
    private final String deviceCode;
    private final String condition;
    private final Integer health;
    private final String risk;
    private final String riskClass;
    private final Double quality;
    private final String ready;
    private final String alarm;
    private final Double temperature;
    private final Double vibration;
    private final Double current;
    private final Integer rulDays;
    private final Instant updatedAt;
    @Version
    private final Long version;

    public DeviceSnapshotDocument(String deviceCode, String condition, Integer health, String risk,
                                  String riskClass, Double quality, String ready, String alarm,
                                  Double temperature, Double vibration, Double current, Integer rulDays,
                                  Instant updatedAt, Long version) {
        this.deviceCode = deviceCode;
        this.condition = condition;
        this.health = health;
        this.risk = risk;
        this.riskClass = riskClass;
        this.quality = quality;
        this.ready = ready;
        this.alarm = alarm;
        this.temperature = temperature;
        this.vibration = vibration;
        this.current = current;
        this.rulDays = rulDays;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    static DeviceSnapshotDocument fromDomain(DeviceSnapshot snapshot) {
        return new DeviceSnapshotDocument(snapshot.deviceCode(), snapshot.condition(), snapshot.health(),
                snapshot.risk(), snapshot.riskClass(), snapshot.quality(), snapshot.ready(), snapshot.alarm(),
                snapshot.temperature(), snapshot.vibration(), snapshot.current(), snapshot.rulDays(),
                snapshot.updatedAt(), snapshot.version());
    }

    DeviceSnapshot toDomain() {
        return new DeviceSnapshot(deviceCode, condition, health, risk, riskClass, quality, ready, alarm,
                temperature, vibration, current, rulDays, updatedAt, version);
    }
}
