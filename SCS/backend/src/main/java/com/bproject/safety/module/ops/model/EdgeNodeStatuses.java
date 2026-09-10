package com.bproject.safety.module.ops.model;

/**
 * 边缘节点状态（SIMULATED EDGE AUTONOMY：同进程模拟，不是真实边缘设备进程）。
 *
 * <ul>
 *   <li>ONLINE：云连接正常；</li>
 *   <li>DEGRADED：云连接正常但存在时钟漂移 / 版本滞后 / 缓存偏高等降级项；</li>
 *   <li>OFFLINE：云连接中断，边缘本地自治继续运行（autonomyActive=true）；</li>
 *   <li>RECOVERING：恢复流程进行中（时间 / 规则对账、事件补传）；</li>
 *   <li>ERROR：节点异常（演示用故障态）。</li>
 * </ul>
 * 中文展示集中在前端 adapter，后端只输出稳定英文枚举。
 */
public final class EdgeNodeStatuses {

    public static final String ONLINE = "ONLINE";
    public static final String DEGRADED = "DEGRADED";
    public static final String OFFLINE = "OFFLINE";
    public static final String RECOVERING = "RECOVERING";
    public static final String ERROR = "ERROR";

    private EdgeNodeStatuses() {
    }

    /** 前端运维页中文标签（集中映射，组件内不再各自翻译）。 */
    public static String chineseLabel(String status) {
        return switch (status) {
            case ONLINE -> "正常";
            case DEGRADED -> "降级";
            case OFFLINE -> "离线";
            case RECOVERING -> "恢复中";
            case ERROR -> "异常";
            default -> status;
        };
    }
}
