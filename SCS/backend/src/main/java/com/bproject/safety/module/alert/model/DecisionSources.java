package com.bproject.safety.module.alert.model;

/**
 * 告警判定 / 配置来源类型（F-07 provenance）。
 *
 * <p>记录一条告警究竟由什么配置或模型判定产生，避免把围栏版本伪装成规则版本：</p>
 * <ul>
 *   <li>{@link #FENCE}：电子围栏空间判定（人员越界），版本对应围栏版本；</li>
 *   <li>{@link #RULE}：安全规则触发，版本对应规则版本；</li>
 *   <li>{@link #AI_MODEL}：AI 模型识别，版本对应模型版本；</li>
 *   <li>{@link #COLLISION}：防碰撞实时判定；</li>
 *   <li>{@link #EDGE}：边缘补传（原始来源见告警内容 / evidence）；</li>
 *   <li>{@link #MANUAL}：人工创建。</li>
 * </ul>
 * 本类只做来源标记，不引入规则引擎 / 决策框架。
 */
public final class DecisionSources {

    public static final String FENCE = "FENCE";
    public static final String RULE = "RULE";
    public static final String AI_MODEL = "AI_MODEL";
    public static final String COLLISION = "COLLISION";
    public static final String EDGE = "EDGE";
    public static final String MANUAL = "MANUAL";

    private DecisionSources() {
    }
}
