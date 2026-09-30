package com.bproject.trust.config;

import com.bproject.trust.shared.json.Json;
import java.nio.file.*;
import java.util.Map;

/** Explicit instance allowlist plus immutable denials for already published resources. */
public record IdentityInstance(
    String root,
    String databaseUrl,
    String appRole,
    String migrationRole,
    String channel,
    int port,
    String queryKeyRef) {
  public record Allowlist(int schemaVersion, Map<String, IdentityInstance> instances) {}

  public static IdentityInstance read(String file, String name) {
    try {
      if (file == null || name == null || name.isBlank() || !Path.of(file).isAbsolute())
        throw new IllegalArgumentException();
      var all = Json.MAPPER.readValue(Files.readString(Path.of(file)), Allowlist.class);
      var i = all.instances().get(name);
      if (all.schemaVersion() != 1 || i == null) throw new IllegalArgumentException();
      i.validate();
      return i;
    } catch (Exception e) {
      throw new IllegalStateException("隔离实例不在允许名单内或配置无效", e);
    }
  }

  static boolean protectedRoot(String root) {
    return root.contains("trust-wallet-remediation")
        || root.startsWith("/data/app/")
        || root.matches("(?s)/home/[^/]+/projects/b-project-trust.*");
  }

  public void validate() {
    if (root == null
        || !Path.of(root).isAbsolute()
        || root.contains("..")
        || !Path.of(root).getFileName().toString().equals("TRUST")
        || protectedRoot(root)
        || databaseUrl == null
        || !databaseUrl.matches("jdbc:opengauss://127\\.0\\.0\\.1:25432/trust_iam_[a-z0-9_]{1,32}")
        || appRole == null
        || !appRole.matches("trust_iam_[a-z0-9_]{1,24}_app")
        || migrationRole == null
        || !migrationRole.matches("trust_iam_[a-z0-9_]{1,24}_migrate")
        || channel == null
        || !channel.matches("trust-iam-[a-z0-9-]{1,32}")
        || port < 1024
        || port > 65535
        || java.util.Set.of(28182, 28183, 18080, 18091).contains(port)
        || queryKeyRef == null
        || !queryKeyRef.matches("[A-Za-z0-9_-]{1,100}"))
      throw new IllegalStateException("拒绝受保护资源或未明确配置的隔离目标");
  }
}
