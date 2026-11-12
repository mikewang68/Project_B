package com.bproject.safety.module.zone.model;

/**
 * 区域实时在场快照（首页"装卸区状态"卡的实时人员感知）。
 *
 * <p>kind=fixed 为站场固定作业区（人员位置按矩形归属）；
 * kind=fence 为已生效临时围栏（人员位置按围栏多边形判定，含隔离带人数）。</p>
 */
public record ZonePresence(
        String code,
        String name,
        String kind,
        int personCount,
        /** 仅围栏：处于隔离带（缓冲区）但未入核心区的人数。 */
        int bufferCount,
        /** 仅围栏：生命周期中文标签（已生效）。 */
        String fenceStatus,
        /** 仅围栏：围栏业务编号。 */
        String fenceId) {

    public static ZonePresence fixed(String name, int personCount) {
        return new ZonePresence("AREA-" + name, name, "fixed", personCount, 0, null, null);
    }

    public static ZonePresence fence(String fenceId, String name, int personCount, int bufferCount, String statusLabel) {
        return new ZonePresence(fenceId, name, "fence", personCount, bufferCount, statusLabel, fenceId);
    }
}
