package com.bproject.trust.ingress;

import com.bproject.trust.identity.IntegrationSettings;
import com.bproject.trust.provisioning.IdentityProviderSettings;
import com.bproject.trust.shared.json.Json;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EvidenceIngressSettings {
  private final Path file;
  private final IntegrationSettings legacy;
  private final IdentityProviderSettings identities;

  public EvidenceIngressSettings(
      @Value("${trust.root}") String root,
      IntegrationSettings legacy,
      IdentityProviderSettings identities) {
    this.file = Path.of(root, "runtime/secrets/evidence-ingress.json");
    this.legacy = legacy;
    this.identities = identities;
  }

  public record Source(
      String id,
      String tokenSha256,
      String orgId,
      String sourceSystem,
      boolean enabled,
      Set<String> operations,
      Set<String> allowedEventTypes) {
    public Source {
      operations = operations == null ? null : Set.copyOf(operations);
      allowedEventTypes = allowedEventTypes == null ? null : Set.copyOf(allowedEventTypes);
    }

    public String actor() {
      return "source:" + id;
    }

    public void require(String operation) {
      if (!enabled || !operations.contains(operation))
        throw new IngressError(403, "SOURCE_OPERATION_DENIED", "来源操作未获授权");
    }

    public void type(String type) {
      if (!allowedEventTypes.contains(type) || Set.of("WAREHOUSE_IN", "DISPATCH").contains(type))
        throw new IngressError(403, "SOURCE_OPERATION_DENIED", "来源事件类型未获授权");
    }
  }

  public record Settings(int schemaVersion, boolean enabled, List<Source> sources) {
    public Settings {
      sources = sources == null ? null : List.copyOf(sources);
    }
  }

  public Settings read() {
    try {
      if (!Files.exists(file)) return new Settings(1, false, List.of());
      var value = Json.MAPPER.readValue(Files.readString(file), Settings.class);
      if (value.schemaVersion() != 1 || value.sources() == null)
        throw new IllegalArgumentException();
      if (!value.enabled()) return new Settings(1, false, List.of());
      var ids = new HashSet<String>();
      var hashes = new HashSet<String>();
      var scopes = new HashSet<String>();
      for (var s : value.sources()) {
        if (s == null
            || !id(s.id(), 70)
            || !id(s.orgId(), 80)
            || !id(s.sourceSystem(), 80)
            || !ids.add(s.id())
            || s.tokenSha256() == null
            || !s.tokenSha256().matches("[a-f0-9]{64}")
            || !hashes.add(s.tokenSha256())
            || !scopes.add(s.orgId() + ":" + s.sourceSystem())
            || s.operations() == null
            || !s.operations().contains("read")
            || !Set.of("read", "submit", "retry").containsAll(s.operations())
            || s.allowedEventTypes() == null
            || s.allowedEventTypes().isEmpty()
            || s.allowedEventTypes().size() > 80
            || s.allowedEventTypes().stream()
                .anyMatch(
                    t ->
                        t == null
                            || !t.matches("[A-Z_]{1,40}")
                            || Set.of("WAREHOUSE_IN", "DISPATCH").contains(t)))
          throw new IllegalArgumentException();
      }
      for (var s : legacy.read().services())
        if (ids.contains(s.id())
            || hashes.contains(s.tokenSha256())
            || scopes.contains(s.orgId() + ":" + s.sourceSystem()))
          throw new IllegalArgumentException();
      if (identities.configured()
          && identities.read().clients().stream().anyMatch(c -> hashes.contains(c.tokenSha256())))
        throw new IllegalArgumentException();
      return value;
    } catch (Exception e) {
      throw new IllegalStateException("通用来源配置不可用", e);
    }
  }

  private static boolean id(String s, int n) {
    return s != null && s.matches("[A-Za-z0-9._-]{1," + n + "}");
  }

  public List<Source> processingSources() {
    try {
      var c = read();
      return c.enabled() ? c.sources().stream().filter(Source::enabled).toList() : List.of();
    } catch (IllegalStateException e) {
      return List.of();
    }
  }

  public boolean permits(Map<String, Object> row) {
    if (!"GENERIC_V1".equals(row.get("record_kind"))) return true;
    return processingSources().stream()
        .anyMatch(
            s ->
                s.orgId().equals(row.get("org_id"))
                    && s.sourceSystem().equals(row.get("source_system"))
                    && s.actor().equals(row.get("submitted_by"))
                    && s.allowedEventTypes().contains(row.get("event_type")));
  }
}
