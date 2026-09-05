package com.bproject.safety.module.projection.shared;

/** 安全态势共享投影 DTO（大屏 / 首页复用，避免两套聚合逻辑）。 */
public final class ProjectionDtos {

    private ProjectionDtos() {
    }

    public record RiskCount(String type, int count) {
    }

    public record TrendPoint(String label, int total, int highRisk, boolean demo) {
    }

    public record DeviceHealth(String name, int online, int total, boolean demo) {
    }
}
