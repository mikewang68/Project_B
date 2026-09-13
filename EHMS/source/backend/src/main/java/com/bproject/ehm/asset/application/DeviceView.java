package com.bproject.ehm.asset.application;

import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.monitoring.domain.model.DeviceSnapshot;

import java.time.Instant;

public record DeviceView(
        String code,
        String name,
        String type,
        String area,
        String condition,
        Integer health,
        String risk,
        String riskClass,
        Double quality,
        String ready,
        String alarm,
        String maintenanceDate,
        String owner,
        Double temperature,
        Double vibration,
        Double current,
        Integer rulDays,
        Instant updatedAt,
        Long version
) {
    static DeviceView compose(Asset asset, DeviceSnapshot snapshot) {
        return new DeviceView(asset.code(), asset.name(), asset.type(), asset.area(),
                snapshot == null ? "待接入" : snapshot.condition(),
                snapshot == null ? null : snapshot.health(),
                snapshot == null ? "待评估" : snapshot.risk(),
                snapshot == null ? "limited" : snapshot.riskClass(),
                snapshot == null ? null : snapshot.quality(),
                snapshot == null ? "待接入" : snapshot.ready(),
                snapshot == null ? "无活动告警" : snapshot.alarm(),
                asset.maintenanceDate(), asset.owner(),
                snapshot == null ? null : snapshot.temperature(),
                snapshot == null ? null : snapshot.vibration(),
                snapshot == null ? null : snapshot.current(),
                snapshot == null ? null : snapshot.rulDays(),
                snapshot == null ? asset.updatedAt() : snapshot.updatedAt(), asset.version());
    }
}
