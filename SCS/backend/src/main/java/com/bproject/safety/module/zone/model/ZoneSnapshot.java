package com.bproject.safety.module.zone.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 装卸区状态快照。
 *
 * <p>两个区用不同传感方案，字段按区各自解释：
 * <ul>
 *   <li>装卸区 A：限位开关压合 → {@code personnelWorking}（作业面上有人作业）；</li>
 *   <li>装卸区 B：位置传感器测距 → {@code vehiclePresent} + {@code distanceMm}（至少判断有无车）。</li>
 * </ul>
 * 未收到上报的字段为 {@code null}，表示"未知"，不要当成 false（无车/无人）。</p>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ZoneSnapshot(
        String code,
        String name,
        String sensorType,
        Boolean personnelWorking,
        Boolean vehiclePresent,
        Integer distanceMm,
        String signal,
        String updatedAt,
        boolean online) {

    /** 未收到任何上报时的初始态：全部未知。 */
    public static ZoneSnapshot unknown(String code, String name, String sensorType) {
        return new ZoneSnapshot(code, name, sensorType, null, null, null, "未接入", null, false);
    }
}
