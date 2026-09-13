package com.bproject.ehm.asset.application;

import com.bproject.ehm.asset.domain.model.Component;

import java.time.Instant;

public record ComponentView(
        String code,
        String assetCode,
        String parentCode,
        String name,
        String category,
        String manufacturer,
        String model,
        String serialNumber,
        String criticality,
        String position,
        String installedOn,
        String status,
        Instant updatedAt,
        Long version
) {
    public static ComponentView from(Component component) {
        return new ComponentView(component.code(), component.assetCode(), component.parentCode(), component.name(),
                component.category(), component.manufacturer(), component.model(), component.serialNumber(),
                component.criticality(), component.position(), component.installedOn(), component.status(),
                component.updatedAt(), component.version());
    }
}
