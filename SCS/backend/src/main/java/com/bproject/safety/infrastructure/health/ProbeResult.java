package com.bproject.safety.infrastructure.health;

/**
 * 基础设施探针结果。
 *
 * @param status UP / DOWN / DISABLED（未启用，不影响就绪判定）
 * @param detail 简短说明（异常摘要，不含密码等敏感信息）
 */
public record ProbeResult(Status status, String detail) {

    public enum Status { UP, DOWN, DISABLED }

    public static ProbeResult up() {
        return new ProbeResult(Status.UP, "OK");
    }

    public static ProbeResult up(String detail) {
        return new ProbeResult(Status.UP, detail);
    }

    public static ProbeResult down(String detail) {
        return new ProbeResult(Status.DOWN, detail);
    }

    public static ProbeResult disabled(String detail) {
        return new ProbeResult(Status.DISABLED, detail);
    }
}
