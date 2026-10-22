package com.bproject.trust.evidence;

import com.bproject.trust.config.IdentityInstance;
import com.bproject.trust.shared.json.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Offline operator entry point. No web endpoint and no implicit repair. */
public final class EvidenceReconcile {
  public static void main(String[] args) throws Exception {
    boolean apply = args.length == 1 && "--apply".equals(args[0]);
    if (args.length > 0 && !apply)
      throw new IllegalArgumentException("Usage: EvidenceReconcile [--apply]");
    var instance =
        IdentityInstance.read(
            System.getenv("TRUST_INSTANCE_ALLOWLIST"), System.getenv("TRUST_INSTANCE"));
    if (!instance.databaseUrl().equals(System.getenv("TRUST_DB_URL")))
      throw new IllegalArgumentException("Dedicated database mismatch");
    var ds =
        new DriverManagerDataSource(
            instance.databaseUrl(), instance.appRole(), System.getenv("TRUST_DB_PASSWORD"));
    ds.setSchema("trust_data");
    System.out.println(
        Json.write(
            new EvidenceService(new JdbcTemplate(ds), null, instance.root()).reconcile(apply)));
  }
}
