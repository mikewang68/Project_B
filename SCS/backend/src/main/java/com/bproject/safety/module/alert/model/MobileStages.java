package com.bproject.safety.module.alert.model;

/**
 * 移动端现场处置阶段常量（Backend Demo 过渡设计）。
 * 不改变管理端主状态机 {@link AlertStatuses}，只细化“待处理”阶段的现场动作。
 */
public final class MobileStages {

    private MobileStages() {
    }

    /** 待接单（派单后、安全员接单前）。 */
    public static final String PENDING = "PENDING";
    /** 已接单。 */
    public static final String ACCEPTED = "ACCEPTED";
    /** 已到场。 */
    public static final String ARRIVED = "ARRIVED";
    /** 处理中（与主状态“处理中”对齐）。 */
    public static final String PROCESSING = "PROCESSING";
}
