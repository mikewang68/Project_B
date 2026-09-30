package com.bproject.trust.config;

import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Runs before bean creation, database connections and Liquibase. */
public final class IsolationGuard implements EnvironmentPostProcessor, Ordered {
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment e, SpringApplication app) {
    if (!e.getProperty("trust.isolation-enabled", Boolean.class, true)) return;
    String url = e.getProperty("spring.datasource.url", "");
    String root = e.getProperty("trust.root", "");
    int port = e.getProperty("server.port", Integer.class, 28182);
    var instance =
        IdentityInstance.read(
            e.getProperty("trust.instance-allowlist"), e.getProperty("trust.instance"));
    var integration = new com.bproject.trust.identity.IntegrationSettings(root).read();
    if (!"real".equals(integration.iamMode())
        || integration.iamUrl().isBlank()
        || e.getProperty("trust.dev-quick-login.enabled", Boolean.class, false))
      throw new IllegalStateException("联合隔离实例必须显式接入真实 IAM 并关闭开发登录");
    if (!url.equals(instance.databaseUrl())
        || !instance.appRole().equals(e.getProperty("spring.datasource.username"))
        || !instance.channel().equals(e.getProperty("trust.fabric-channel"))
        || !instance.queryKeyRef().equals(e.getProperty("trust.fabric-query-key-ref", ""))
        || !"127.0.0.1".equals(e.getProperty("server.address"))
        || port != instance.port()
        || e.getProperty("spring.liquibase.enabled", Boolean.class, true)
        || root.isBlank()
        || !Path.of(root).normalize().equals(Path.of(instance.root()).normalize())
        || Path.of(root).normalize().toString().equals("/data/app/trust"))
      throw new IllegalStateException("隔离配置拒绝启动：必须显式指定专用库、受限用户、专用通道、回环端口并禁用运行时迁移");
  }
}
