package com.bproject.trust.identity;

import com.bproject.trust.shared.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deployment-owned trust boundaries; never accepted from browser input. */
@Component
public class IntegrationSettings {
  private final Path file;
  private final boolean developmentLoginEnabled;

  public IntegrationSettings(String root) {
    this(root, false);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public IntegrationSettings(
      @Value("${trust.root}") String root,
      @Value("${trust.dev-quick-login.enabled:false}") boolean developmentLoginEnabled) {
    file = Path.of(root, "runtime/secrets/integration.json");
    this.developmentLoginEnabled = developmentLoginEnabled;
  }

  public boolean localLoginEnabled(Settings configuration) {
    return "development".equals(configuration.iamMode())
        || (configuration.simulated() && developmentLoginEnabled);
  }

  public record Service(
      String id,
      String tokenSha256,
      String orgId,
      String sourceSystem,
      String companyCode,
      String warehouseCode,
      String ownerCode) {}

  public record Settings(
      String iamUrl,
      String iamMode,
      boolean isolationEnabled,
      Map<String, String> orgMap,
      List<Service> services) {
    public Settings(String iamUrl, Map<String, String> orgMap, List<Service> services) {
      this(iamUrl, "real", false, orgMap, services);
    }

    public Settings(
        String iamUrl, String iamMode, Map<String, String> orgMap, List<Service> services) {
      this(iamUrl, iamMode, false, orgMap, services);
    }

    public boolean simulated() {
      return isolationEnabled && "simulated".equals(iamMode);
    }

    public boolean iamRequired() {
      return !"development".equals(iamMode);
    }
  }

  public Settings read() {
    try {
      if (!Files.exists(file)) return new Settings("", "development", false, Map.of(), List.of());
      var value = Json.MAPPER.readValue(Files.readString(file), Settings.class);
      if (value.iamMode() == null)
        value =
            new Settings(
                value.iamUrl(), "real", value.isolationEnabled(), value.orgMap(), value.services());
      if (value.orgMap() == null || value.services() == null || value.iamUrl() == null)
        throw new IllegalArgumentException();
      if (!SetModes.ALLOWED.contains(value.iamMode())
          || ("simulated".equals(value.iamMode()) && !value.isolationEnabled()))
        throw new IllegalArgumentException("模拟身份必须明确启用隔离测试配置");
      var ids = new java.util.HashSet<String>();
      var hashes = new java.util.HashSet<String>();
      for (var s : value.services()) {
        if (s.id() == null
            || !s.id().matches("[A-Za-z0-9._-]{1,70}")
            || !ids.add(s.id())
            || s.tokenSha256() == null
            || !s.tokenSha256().matches("[a-f0-9]{64}")
            || !hashes.add(s.tokenSha256())
            || s.orgId() == null
            || !s.orgId().matches("[A-Za-z0-9._-]{1,80}")
            || s.sourceSystem() == null
            || !s.sourceSystem().matches("[A-Za-z0-9._-]{1,80}")
            || s.companyCode() == null
            || s.companyCode().isBlank()
            || s.warehouseCode() == null
            || s.warehouseCode().isBlank()
            || s.ownerCode() == null
            || s.ownerCode().isBlank()) throw new IllegalArgumentException("来源身份配置不完整或重复");
      }
      return value;
    } catch (Exception e) {
      throw new IllegalStateException("身份接入配置不可用", e);
    }
  }

  private static final class SetModes {
    private static final java.util.Set<String> ALLOWED =
        java.util.Set.of("real", "simulated", "development");
  }
}
