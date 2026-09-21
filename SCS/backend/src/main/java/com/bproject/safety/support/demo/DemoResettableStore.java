package com.bproject.safety.support.demo;

/**
 * Demo 维护能力：把存储恢复为初始 Demo 种子数据。
 *
 * <p>落库前运行边界收口（Phase B）：{@code resetDemoData()} <b>不属于</b>正式 Repository 业务契约，
 * 未来 openGauss 实现不得提供“清空并重建生产数据”的能力。只有 Demo 种子初始化器
 * （{@code DemoSeedInitializer}）与测试夹具允许依赖本接口；业务 Service 不得注入。</p>
 */
public interface DemoResettableStore {

    /** 恢复为初始 Demo 种子（仅 Demo / Test 维护用途）。 */
    void resetDemoData();
}
