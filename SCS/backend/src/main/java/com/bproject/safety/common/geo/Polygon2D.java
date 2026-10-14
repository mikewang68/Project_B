package com.bproject.safety.common.geo;

import com.bproject.safety.module.fence.model.FencePoint;
import java.util.ArrayList;
import java.util.List;

/**
 * 二维百分比坐标几何工具（Backend Demo）：用于电子围栏 Point-In-Polygon 判定与外围逻辑缓冲区计算。
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

    /**
     * 将多边形按指定缓冲距离向外扩展，生成外围逻辑缓冲区（隔离带）多边形。
     * 基于质心向各顶点方向外扩投影，并钳位至 [0, 100] 地图画布百分比范围。
     */
    public static List<FencePoint> expandPolygon(List<FencePoint> polygon, double bufferDistance) {
        if (polygon == null || polygon.isEmpty()) {
            return new ArrayList<>();
        }
        if (polygon.size() < 3 || bufferDistance <= 0) {
            return new ArrayList<>(polygon);
        }
        double cx = polygon.stream().mapToDouble(FencePoint::x).average().orElse(50.0);
        double cy = polygon.stream().mapToDouble(FencePoint::y).average().orElse(50.0);
        List<FencePoint> expanded = new ArrayList<>(polygon.size());
        for (FencePoint p : polygon) {
            double dx = p.x() - cx;
            double dy = p.y() - cy;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-4) {
                expanded.add(p);
            } else {
                double ex = clamp(p.x() + (dx / len) * bufferDistance, 0.0, 100.0);
                double ey = clamp(p.y() + (dy / len) * bufferDistance, 0.0, 100.0);
                expanded.add(new FencePoint(round(ex), round(ey)));
            }
        }
        return expanded;
    }

    /**
     * 计算点 (px, py) 到多边形各边轮廓的最小欧式距离。
     */
    public static double distanceToPolygon(double px, double py, List<FencePoint> polygon) {
        if (polygon == null || polygon.size() < 2) {
            return Double.MAX_VALUE;
        }
        double minDistance = Double.MAX_VALUE;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            FencePoint p1 = polygon.get(j);
            FencePoint p2 = polygon.get(i);
            double dist = pointToSegmentDistance(px, py, p1.x(), p1.y(), p2.x(), p2.y());
            if (dist < minDistance) {
                minDistance = dist;
            }
        }
        return round(minDistance);
    }

    private static double pointToSegmentDistance(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double l2 = dx * dx + dy * dy;
        if (l2 < 1e-8) {
            return Math.sqrt((px - x1) * (px - x1) + (py - y1) * (py - y1));
        }
        double t = Math.max(0.0, Math.min(1.0, ((px - x1) * dx + (py - y1) * dy) / l2));
        double projX = x1 + t * dx;
        double projY = y1 + t * dy;
        return Math.sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY));
    }

    /**
     * 判断是否在围栏外围逻辑缓冲区（隔离带）：不在内部且距离边缘 <= bufferDistance。
     */
    public static boolean isInBufferZone(double px, double py, List<FencePoint> polygon, double bufferDistance) {
        if (polygon == null || polygon.size() < 3 || bufferDistance <= 0) {
            return false;
        }
        List<double[]> coords = polygon.stream().map(FencePoint::xy).toList();
        if (contains(px, py, coords)) {
            return false; // 已在物理红线内部
        }
        return distanceToPolygon(px, py, polygon) <= bufferDistance;
    }

    /**
     * 在外围逻辑缓冲区（隔离带）内选取一个模拟靠近点（距离红线约为 bufferDistance * 0.5）。
     */
    public static FencePoint bufferApproachPoint(List<FencePoint> polygon, double bufferDistance) {
        if (polygon == null || polygon.size() < 3) {
            return new FencePoint(50.0, 50.0);
        }
        double cx = polygon.stream().mapToDouble(FencePoint::x).average().orElse(50.0);
        double cy = polygon.stream().mapToDouble(FencePoint::y).average().orElse(50.0);
        FencePoint p0 = polygon.get(0);
        double dx = p0.x() - cx;
        double dy = p0.y() - cy;
        double len = Math.sqrt(dx * dx + dy * dy);
        double offset = Math.max(1.0, bufferDistance * 0.5);
        if (len > 1e-4) {
            double ax = clamp(p0.x() + (dx / len) * offset, 0.0, 100.0);
            double ay = clamp(p0.y() + (dy / len) * offset, 0.0, 100.0);
            return new FencePoint(round(ax), round(ay));
        }
        return new FencePoint(round(p0.x() + offset), round(p0.y() + offset));
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}

