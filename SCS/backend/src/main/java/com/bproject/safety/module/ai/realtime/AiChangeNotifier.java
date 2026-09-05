package com.bproject.safety.module.ai.realtime;

import com.bproject.safety.module.ai.model.DemoAiEvent;

/**
 * AI 事件变更实时通知端口：AiEventService 在成功写操作（且非幂等回放）后调用。
 * 广播失败不得导致业务回滚，实现内部吞掉异常；单元测试可使用 {@link #NOOP}。
 */
@FunctionalInterface
public interface AiChangeNotifier {

    /**
     * @param op    new / reviewed / changed
     * @param after 变更后的最新 AI 事件副本
     */
    void changed(String op, DemoAiEvent after);

    AiChangeNotifier NOOP = (op, after) -> {
    };
}
