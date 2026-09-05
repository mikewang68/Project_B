package com.bproject.safety.module.personnel.model;

/**
 * 人员历史轨迹点（相对安全地图百分比坐标 + ISO-8601 时间）。
 */
public record TrackPoint(double x, double y, String time) {
}
