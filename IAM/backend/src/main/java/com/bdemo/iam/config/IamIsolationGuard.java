package com.bdemo.iam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

public final class IamIsolationGuard implements EnvironmentPostProcessor, Ordered {
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment e, SpringApplication app) {
    if (!e.getProperty("iam.isolation-enabled", Boolean.class, true)) return;
    try {
      var path = Path.of(e.getRequiredProperty("iam.instance-allowlist"));
      var config = new ObjectMapper().readTree(Files.readString(path));
      var item = config.path("instances").path(e.getRequiredProperty("iam.instance"));
      String root = item.path("root").asText(),
          url = item.path("databaseUrl").asText(),
          role = item.path("appRole").asText();
      int port = item.path("port").asInt();
      if (!path.isAbsolute()
          || config.path("schemaVersion").asInt() != 1
          || !Path.of(root).isAbsolute()
          || !Path.of(root).getFileName().toString().equals("IAM")
          || root.contains("..")
          || root.startsWith("/data/app/")
          || !url.matches(
              "jdbc:postgresql://127\\.0\\.0\\.1:25432/iam_identity_[a-z0-9_]{1,32}\\?currentSchema=iam")
          || !role.matches("iam_identity_[a-z0-9_]{1,24}_app")
          || port < 1024
          || port > 65535
          || java.util.Set.of(18080, 18091, 28182, 28183).contains(port)
          || !url.equals(e.getProperty("spring.datasource.url"))
          || !role.equals(e.getProperty("spring.datasource.username"))
          || !"127.0.0.1".equals(e.getProperty("server.address"))
          || port != e.getProperty("server.port", Integer.class, 0)
          || !root.equals(e.getProperty("iam.root"))
          || e.getProperty("app.jwt.secret", "").length() < 32
          || e.getProperty("app.jwt.secret", "").contains("dev-only")
          || e.getProperty("app.jwt.secret", "").contains("REPLACE")
          || e.getProperty("iam.identity.idempotency-secret", "").contains("REPLACE")
          || e.getProperty("app.jwt.secret", "")
              .equals(e.getProperty("iam.identity.idempotency-secret", ""))
          || e.getProperty("iam.identity.idempotency-secret", "").length() < 32
          || e.getProperty("iam.identity.config", "").isBlank())
        throw new IllegalArgumentException();
    } catch (Exception ex) {
      throw new IllegalStateException("IAM 隔离启动被拒绝：实例、库、账号、回环端口或独立认证配置不匹配", ex);
    }
  }
}
