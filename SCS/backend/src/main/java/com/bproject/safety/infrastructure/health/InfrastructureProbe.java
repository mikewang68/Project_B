package com.bproject.safety.infrastructure.health;

/** 基础设施健康探针：openGauss / Kvrocks / RocketMQ / openGemini 各自实现。 */
public interface InfrastructureProbe {

    /** readiness.components 中使用的稳定键名，如 database / cache / rocketmq / openGemini。 */
    String componentName();

    /** 执行一次探测，实现内部必须吞掉异常并返回 DOWN，而不是向上抛出。 */
    ProbeResult probe();
}
