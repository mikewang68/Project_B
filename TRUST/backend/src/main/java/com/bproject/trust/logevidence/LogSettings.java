package com.bproject.trust.logevidence;

import com.bproject.trust.ingress.IngressError;
import com.bproject.trust.shared.json.Json;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LogSettings {
  private final Path file;

  public LogSettings(@Value("${trust.root}") String root) {
    file = Path.of(root, "runtime/secrets/audit-log.json");
  }

  public record Scope(String domainId, String zoneId, String appCode) {}

  public record Source(
      String id,
      String tokenSha256,
      String genericSourceId,
      Set<String> operations,
      Set<Scope> scopes) {
    public void require(String op) {
      if (!operations.contains(op)) throw new IngressError(403, "LOG_OPERATION_DENIED", "日志操作未获授权");
    }

    public void scope(String domain, String zone, String app) {
      if (!scopes.contains(new Scope(domain, zone, app)))
        throw new IngressError(404, "LOG_NOT_FOUND", "日志不存在");
    }
  }

  public record Delivery(
      boolean enabled, String url, String tokenFile, Map<String, Scope> orgScopes) {}

  public record Config(
      int schemaVersion,
      boolean enabled,
      String activeKeyId,
      String keyRingFile,
      Set<String> managerUserIds,
      List<Source> sources,
      Delivery delivery) {}

  public Config read() {
    try {
      if (!Files.exists(file))
        return new Config(
            1, false, "", "", Set.of(), List.of(), new Delivery(false, "", "", Map.of()));
      Config c = Json.MAPPER.readValue(Files.readString(file), Config.class);
      if (c.schemaVersion() != 1
          || c.managerUserIds() == null
          || c.sources() == null
          || c.delivery() == null
          || c.delivery().orgScopes() == null) throw new IllegalArgumentException();
      Set<String> ids = new HashSet<>(), hashes = new HashSet<>();
      for (Source s : c.sources()) {
        LogEvent.identifier(s.id(), 70);
        LogEvent.identifier(s.genericSourceId(), 70);
        if (!ids.add(s.id())
            || !hashes.add(s.tokenSha256())
            || !s.tokenSha256().matches("[a-f0-9]{64}")
            || s.scopes() == null
            || s.scopes().isEmpty()
            || s.operations() == null
            || !s.operations().contains("read")
            || !Set.of("read", "submit", "retry", "restore").containsAll(s.operations()))
          throw new IllegalArgumentException();
        for (Scope scope : s.scopes()) {
          LogEvent.identifier(scope.domainId(), 128);
          LogEvent.identifier(scope.zoneId(), 128);
          LogEvent.identifier(scope.appCode(), 64);
        }
      }
      // A log-service credential must never double as WMS, generic ingress or identity credentials.
      for (String name :
          List.of("integration.json", "evidence-ingress.json", "identity-provider.json")) {
        Path neighbor = file.getParent().resolve(name);
        if (Files.exists(neighbor))
          for (var value :
              Json.MAPPER.readTree(Files.readString(neighbor)).findValuesAsText("tokenSha256"))
            if (hashes.contains(value)) throw new IllegalArgumentException();
      }
      if (c.enabled()
          && (c.activeKeyId() == null
              || !c.activeKeyId().matches("[A-Za-z0-9_-]{1,64}")
              || c.keyRingFile() == null
              || !Path.of(c.keyRingFile()).isAbsolute())) throw new IllegalArgumentException();
      if (c.delivery().enabled()) {
        var uri = java.net.URI.create(c.delivery().url());
        if (!Set.of("http", "https").contains(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null
            || uri.getFragment() != null
            || uri.getQuery() != null
            || !Path.of(c.delivery().tokenFile()).isAbsolute())
          throw new IllegalArgumentException();
      }
      return c;
    } catch (Exception e) {
      throw new IngressError(503, "LOG_CONFIGURATION_UNAVAILABLE", "日志配置不可用");
    }
  }
}
