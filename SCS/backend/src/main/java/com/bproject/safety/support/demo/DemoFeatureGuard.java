package com.bproject.safety.support.demo;

import com.bproject.safety.common.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Demo 能力统一开关（落库前运行边界收口 F-11）。
 *
 * <ul>
 *   <li>{@code app.demo.seed-enabled}：启动时是否灌入 Demo 种子（默认 false，dev profile 打开）。</li>
 *   <li>{@code app.demo.simulator-enabled}：是否开放各类 simulate、demoRisk、surge、mismatch、
 *       offline 等模拟端点（默认 false，dev profile 打开）。</li>
 * </ul>
 *
 * <p>真实业务查询 / 处置接口不受此开关影响；关闭时模拟端点返回 403 DEMO_FEATURE_DISABLED，
 * 不再“200 但什么都不做”。服务器 / 未来生产环境必须显式设置环境变量才会开启 Demo 能力。</p>
 */
@Component
public class DemoFeatureGuard {

    public static final String DEMO_FEATURE_DISABLED = "DEMO_FEATURE_DISABLED";

    private final boolean seedEnabled;
    private final boolean simulatorEnabled;

    public DemoFeatureGuard(
            @Value("${app.demo.seed-enabled:false}") boolean seedEnabled,
            @Value("${app.demo.simulator-enabled:false}") boolean simulatorEnabled) {
        this.seedEnabled = seedEnabled;
        this.simulatorEnabled = simulatorEnabled;
    }

    public boolean isSeedEnabled() {
        return seedEnabled;
    }

    public boolean isSimulatorEnabled() {
        return simulatorEnabled;
    }

    /** 模拟端点入口调用；关闭时直接 403，不执行业务模拟。 */
    public void requireSimulator() {
        if (!simulatorEnabled) {
            throw ApiException.forbidden(DEMO_FEATURE_DISABLED, "Demo 模拟功能已关闭（app.demo.simulator-enabled=false）");
        }
    }
}
