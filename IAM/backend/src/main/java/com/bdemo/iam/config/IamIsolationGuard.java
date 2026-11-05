package com.bdemo.iam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
      String profile=item.path("deploymentProfile").asText();
      boolean candidate="ISOLATED".equals(profile),restore="RESTORE".equals(profile);
      if(!url.startsWith("jdbc:postgresql://"))throw new IllegalArgumentException();
      URI database=URI.create(url.substring("jdbc:".length()));
      String databaseName=item.path("databaseName").asText();
      String host=item.path("databaseHost").asText();
      int databasePort=item.path("databasePort").asInt();
      boolean explicitDatabase=java.util.Set.of("STANDARD","ISOLATED","RESTORE").contains(profile)
          &&databaseName.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
          &&role.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
          &&!java.util.Set.of("omm","postgres","root").contains(role.toLowerCase(java.util.Locale.ROOT))
          &&!host.isBlank()&&host.equalsIgnoreCase(database.getHost())
          &&databasePort>0&&databasePort<=65535&&databasePort==database.getPort()
          &&("/"+databaseName).equals(database.getRawPath())
          &&"currentSchema=iam".equals(database.getRawQuery())
          &&database.getRawUserInfo()==null&&database.getRawFragment()==null
          &&(!(candidate||restore)||item.path("databaseOid").asLong()>0);
      boolean restoreTarget=restore&&explicitDatabase
          && !e.getProperty("iam.identity.delivery-enabled",Boolean.class,true)
          && !e.getProperty("iam.audit.public.enabled",Boolean.class,false)
          && !e.getProperty("sys.audit.public.enabled",Boolean.class,false)
          && e.getProperty("app.sso.logout-endpoints","").isBlank();
      if (!path.isAbsolute()
          || config.path("schemaVersion").asInt() != 1
          || !Path.of(root).isAbsolute()
          || !Path.of(root).getFileName().toString().equals("IAM")
          || root.contains("..")
          || !explicitDatabase
          || restore&&!restoreTarget
          || port < 1024
          || port > 65535
          || !config.path("blockedPorts").isArray()
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
          || !restoreTarget && e.getProperty("iam.identity.config", "").isBlank())
        throw new IllegalArgumentException();
      for(var blocked:config.path("blockedPorts"))
        if(!blocked.isIntegralNumber()||blocked.asInt()<1||blocked.asInt()>65535||blocked.asInt()==port)
          throw new IllegalArgumentException();
      if(candidate || restoreTarget) {
        // The explicitly registered isolated database must match its immutable OID.
        Class.forName("org.postgresql.Driver");
        try(var connection=java.sql.DriverManager.getConnection(url,role,e.getRequiredProperty("spring.datasource.password"));
            var statement=connection.createStatement();
            var result=statement.executeQuery("SELECT oid FROM pg_database WHERE datname=current_database() AND datcompatibility='PG'")) {
          if(!result.next()||result.getLong(1)!=item.path("databaseOid").asLong())throw new IllegalArgumentException();
        }
      }
    } catch (Exception ex) {
      throw new IllegalStateException("IAM 隔离启动被拒绝：实例、库、账号、回环端口或独立认证配置不匹配", ex);
    }
  }
}
