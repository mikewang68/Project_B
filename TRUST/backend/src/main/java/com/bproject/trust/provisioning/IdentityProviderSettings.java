package com.bproject.trust.provisioning;

import com.bproject.trust.identity.IntegrationSettings;
import com.bproject.trust.shared.json.Json;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IdentityProviderSettings {
  private final Path file;
  private final IntegrationSettings integrations;

  public IdentityProviderSettings(
      @Value("${trust.root}") String root, IntegrationSettings integrations) {
    file = Path.of(root, "runtime/secrets/identity-provider.json");
    this.integrations = integrations;
  }

  public record Client(
      String id,
      String tokenSha256,
      String issuer,
      String tenant,
      Set<String> orgs,
      Set<String> operations) {}

  public record Organization(
      String mspId,
      String caId,
      String affiliation,
      Map<String, String> attributes,
      String caUrl,
      String tlsRootFile,
      String enrollmentRootFile,
      String registrarHome) {}

  public record Settings(
      String clientBinary,
      String clientVersion,
      String clientSha256,
      List<Client> clients,
      Map<String, Organization> organizations) {}

  public boolean configured() {
    return Files.isRegularFile(file);
  }

  public Settings read() {
    try {
      var s = Json.MAPPER.readValue(Files.readString(file), Settings.class);
      if (s.clients() == null
          || s.organizations() == null
          || s.clients().isEmpty()
          || s.organizations().isEmpty()
          || !Path.of(s.clientBinary()).isAbsolute()
          || !s.clientSha256().matches("[a-f0-9]{64}")
          || !"1.5.22".equals(s.clientVersion())) throw new IllegalArgumentException();
      var hashes = new HashSet<String>();
      var ids = new HashSet<String>();
      var owners = new HashSet<String>();
      for (var c : s.clients()) {
        if (!id(c.id())
            || !id(c.issuer())
            || !id(c.tenant())
            || !ids.add(c.id())
            || !owners.add(c.issuer() + ":" + c.tenant())
            || !c.tokenSha256().matches("[a-f0-9]{64}")
            || !hashes.add(c.tokenSha256())
            || c.orgs() == null
            || c.orgs().isEmpty()
            || !s.organizations().keySet().containsAll(c.orgs())
            || c.operations() == null
            || !c.operations().contains("read")
            || !Set.of("read", "create", "retry", "rotate", "disable", "revoke")
                .containsAll(c.operations())) throw new IllegalArgumentException();
      }
      if (integrations.read().services().stream().anyMatch(c -> hashes.contains(c.tokenSha256())))
        throw new IllegalArgumentException("Identity credential must not reuse ingress credential");
      for (var e : s.organizations().entrySet()) {
        var o = e.getValue();
        var uri = java.net.URI.create(o.caUrl());
        if (!id(e.getKey())
            || !id(o.mspId())
            || !id(o.caId())
            || !o.affiliation().matches("[a-zA-Z0-9_.-]{1,128}")
            || !"https".equals(uri.getScheme())
            || uri.getUserInfo() != null
            || uri.getQuery() != null
            || uri.getFragment() != null
            || !Path.of(o.tlsRootFile()).isAbsolute()
            || !Path.of(o.enrollmentRootFile()).isAbsolute()
            || !Path.of(o.registrarHome()).isAbsolute()
            || o.attributes() == null
            || o.attributes().keySet().stream()
                .anyMatch(k -> !k.matches("app\\.[A-Za-z0-9_.-]{1,64}"))
            || o.attributes().values().stream().anyMatch(v -> !v.matches("[A-Za-z0-9_.-]{1,128}")))
          throw new IllegalArgumentException();
      }
      return s;
    } catch (Exception e) {
      throw new IllegalStateException("身份供给配置不可用", e);
    }
  }

  public static boolean id(String value) {
    return value != null && value.matches("[A-Za-z0-9._-]{1,80}");
  }
}
