package com.bproject.safety.module.ops.model;

/**
 * 边缘节点种子版本常量（Phase B：从 InMemory 实现类迁出的中立常量）。
 *
 * <p>Service 层需要引用初始规则 / 围栏版本作为对账回退值，但不得依赖某个具体 Repository 实现类，
 * 因此常量放在领域 model 包。值与 RuleDemoSeeder 中 RULE-PER-001 当前版本保持一致。</p>
 */
public final class EdgeSeedVersions {

    /** 初始平台规则版本（与 RuleDemoSeeder 中 RULE-PER-001 当前版本一致）。 */
    public static final String RULE_VERSION = "v3.3";
    /** 初始平台围栏版本。 */
    public static final String FENCE_VERSION = "v3.3";

    private EdgeSeedVersions() {
    }
}
