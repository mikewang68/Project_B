package com.bproject.safety.module.fence.model;

/** 围栏顶点（相对安全地图百分比坐标，正式坐标体系后续统一）。 */
public record FencePoint(double x, double y) {
    public double[] xy() {
        return new double[]{x, y};
    }
}
