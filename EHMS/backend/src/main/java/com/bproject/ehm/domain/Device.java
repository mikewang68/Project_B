package com.bproject.ehm.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("devices")
public record Device(
        @Id String code,
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
        Instant updatedAt
) {
}
