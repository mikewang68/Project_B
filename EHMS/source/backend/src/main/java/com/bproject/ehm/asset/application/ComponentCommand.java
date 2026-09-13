package com.bproject.ehm.asset.application;

public record ComponentCommand(
        String code,
        String parentCode,
        String name,
        String category,
        String manufacturer,
        String model,
        String serialNumber,
        String criticality,
        String position,
        String installedOn,
        String status
) {
}
