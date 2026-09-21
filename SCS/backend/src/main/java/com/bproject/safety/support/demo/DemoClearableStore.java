package com.bproject.safety.support.demo;

/**
 * Demo 维护能力：清空存储中的全部数据。
 *
 * <p>落库前运行边界收口（Phase B）：{@code clearDemoData()} <b>不属于</b>正式 Repository 业务契约，
 * 未来 openGauss 实现不得提供“清空生产表”的能力。只有 Demo 种子初始化器、Demo 场景维护组件
 * （如风险突增演示回滚）与测试夹具允许依赖本接口；业务 Service 不得注入。</p>
 */
public interface DemoClearableStore {

    /** 清空全部数据（仅 Demo / Test 维护用途）。 */
    void clearDemoData();
}
