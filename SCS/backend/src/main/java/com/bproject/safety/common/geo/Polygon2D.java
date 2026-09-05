package com.bproject.safety.common.geo;

import java.util.List;

/**
 * 二维百分比坐标几何工具（Backend Demo）：仅用于电子围栏 Point-In-Polygon 判定，
 * 不引入 GIS 库；正式坐标体系（投影 / 米制距离）后续领域设计阶段再统一。
 */
public final class Polygon2D {

    private Polygon2D() {
    }

    /** 点是否在多边形内部（射线法，边界点按内部处理）。coords 为按顺序排列的顶点。 */
    public static boolean contains(double px, double py, List<double[]> coords) {
        if (coords == null || coords.size() < 3) {
            return false;
        }
        boolean inside = false;
        int n = coords.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = coords.get(i)[0];
            double yi = coords.get(i)[1];
            double xj = coords.get(j)[0];
            double yj = coords.get(j)[1];
            boolean intersect = ((yi > py) != (yj > py))
                    && (px < (xj - xi) * (py - yi) / ((yj - yi) == 0 ? 1e-9 : (yj - yi)) + xi);
            if (intersect) {
                inside = !inside;
            }
            // 点落在边线上视为内部
            if (onSegment(px, py, xi, yi, xj, yj)) {
                return true;
            }
        }
        return inside;
    }

    private static boolean onSegment(double px, double py, double x1, double y1, double x2, double y2) {
        double cross = (px - x1) * (y2 - y1) - (py - y1) * (x2 - x1);
        if (Math.abs(cross) > 1e-6) {
            return false;
        }
        return px >= Math.min(x1, x2) - 1e-6 && px <= Math.max(x1, x2) + 1e-6
                && py >= Math.min(y1, y2) - 1e-6 && py <= Math.max(y1, y2) + 1e-6;
    }
}
