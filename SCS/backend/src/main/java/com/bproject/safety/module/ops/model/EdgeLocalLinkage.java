package com.bproject.safety.module.ops.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 边缘本地联动结果（SIMULATED：不连接真实 PLC / 声光硬件）。
 *
 * <p>任务书第十六节：即使 cloudConnected=false，本地联动仍可以 success，
 * 这正是“断网安全自治”的演示重点。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EdgeLocalLinkage {

    /** 现场声光报警。 */
    public boolean alarm;
    /** 现场大屏提示。 */
    public boolean screen;
    /** 本地语音提醒。 */
    public boolean localVoice;
    /** 本地警示灯。 */
    public boolean localLight;
    /** PLC 停车指令结果：success / fail / skipped（Demo，不连真实 PLC）。 */
    public String plcStop;
    /** 联动结果文本摘要（前端事件行动列表）。 */
    public java.util.List<String> actions = new java.util.ArrayList<>();

    public EdgeLocalLinkage() {
    }

    public static EdgeLocalLinkage success(java.util.List<String> actions, boolean needPlcStop) {
        EdgeLocalLinkage l = new EdgeLocalLinkage();
        l.alarm = true;
        l.screen = true;
        l.localVoice = true;
        l.localLight = true;
        l.plcStop = needPlcStop ? "success" : "skipped";
        l.actions = new java.util.ArrayList<>(actions);
        return l;
    }

    /** 拷贝构造（仓储 copy-on-read/write：副本之间不得共享可变 actions 列表）。 */
    public EdgeLocalLinkage(EdgeLocalLinkage o) {
        if (o == null) {
            return;
        }
        this.alarm = o.alarm;
        this.screen = o.screen;
        this.localVoice = o.localVoice;
        this.localLight = o.localLight;
        this.plcStop = o.plcStop;
        this.actions = o.actions == null ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(o.actions);
    }
}
