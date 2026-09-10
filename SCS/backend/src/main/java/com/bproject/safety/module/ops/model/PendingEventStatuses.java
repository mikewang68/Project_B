package com.bproject.safety.module.ops.model;

/** 离线事件队列状态（任务书第十二节）。 */
public final class PendingEventStatuses {

    /** 待补传。 */
    public static final String PENDING = "PENDING";
    /** 补传中。 */
    public static final String SYNCING = "SYNCING";
    /** 已补传（云端已消费并生成 Alert）。 */
    public static final String SYNCED = "SYNCED";
    /** 补传失败，可 Retry。 */
    public static final String FAILED = "FAILED";
    /** 重复事件被幂等去重，未再次生成 Alert。 */
    public static final String DUPLICATE = "DUPLICATE";

    /** 仍占用待补传队列（前端“待补传”计数口径）。 */
    public static boolean pendingLike(String status) {
        return PENDING.equals(status) || SYNCING.equals(status) || FAILED.equals(status);
    }

    private PendingEventStatuses() {
    }
}
