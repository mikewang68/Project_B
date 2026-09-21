package com.bproject.safety.module.fence.service;

/**
 * 围栏业务编号生成器（Phase B 抽象）。
 *
 * <p>把 {@code FENCE-NNN} 编号生成从 FenceService 抽离；当前 Demo 实现基于单仓储计数，
 * <b>不保证多实例唯一</b>；openGauss 阶段可替换为序列实现。</p>
 */
public interface FenceNumberGenerator {

    /** 生成下一个围栏业务编号（格式如 FENCE-005）。 */
    String nextFenceNumber();
}
