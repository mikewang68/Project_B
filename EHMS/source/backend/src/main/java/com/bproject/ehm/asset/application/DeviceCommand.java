package com.bproject.ehm.asset.application;

public record DeviceCommand(
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
        Integer rulDays
) {
}
