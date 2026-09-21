package com.bproject.safety.module.alert.service;

/**
 * 告警业务编号生成器（Phase B 抽象）。
 *
 * <p>把 {@code ALM-yyyyMMdd-NNN} 编号生成从 AlertService 抽离：当前 Demo 实现基于单仓储计数，
 * <b>不保证多实例唯一</b>；未来 openGauss 阶段可替换为日序列 / 号段 / UUIDv7+可读编号的实现，
 * AlertService 无需改动。</p>
 */
public interface AlertNumberGenerator {

    /** 生成下一个告警业务编号（格式如 ALM-20260920-001）。 */
    String nextAlertNumber();
}
