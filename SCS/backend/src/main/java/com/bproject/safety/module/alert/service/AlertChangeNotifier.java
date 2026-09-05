package com.bproject.safety.module.alert.service;

import com.bproject.safety.module.alert.model.DemoAlert;

/**
 * 告警变更实时通知端口：AlertService 在每次成功写操作（且非幂等回放）后调用。
 *
 * <p>WebSocket 属于通知能力：广播失败不得导致业务写操作回滚，因此实现内部必须吞掉异常。
 * 单元测试可使用 {@link #NOOP}。</p>
 */
@FunctionalInterface
public interface AlertChangeNotifier {

    /**
     * @param op    AlertService 内部操作名（confirm/assign/start/treatment/review/...）
     * @param after 变更后的最新告警副本
     */
    void changed(String op, DemoAlert after);

    AlertChangeNotifier NOOP = (op, after) -> {
    };
}
