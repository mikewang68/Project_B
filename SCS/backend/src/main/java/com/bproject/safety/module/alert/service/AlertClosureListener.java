package com.bproject.safety.module.alert.service;

import com.bproject.safety.module.alert.model.DemoAlert;

/**
 * 复查-D6：告警闭环回调接口。Alert 主链进入 CLOSED 后通知跨聚合订阅方
 * （当前订阅方：AI 事件镜像同步），以监听器解掉 Alert→AI 的模块循环依赖。
 * 实现方必须自行保证幂等（回调可能因多关闭路径触发多次）。
 */
public interface AlertClosureListener {

    /** 告警进入 CLOSED 后回调。 */
    void onAlertClosed(DemoAlert alert);
}
