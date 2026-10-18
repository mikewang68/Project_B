package com.bproject.trust.support;

/** Explicit local-only compatibility testing does not relax the production instance allowlist. */
public final class TestDatabase {
  private TestDatabase() {}

  public static String require(String url) {
    String expected =
        "jdbc:"
            + (postgresql() ? "postgresql" : "opengauss")
            + "://127.0.0.1:25432/trust_iam_dev_test";
    if (!expected.equals(url))
      throw new IllegalStateException("Dedicated local test database required");
    return url;
  }

  public static boolean postgresql() {
    return "1".equals(System.getenv("TRUST_TEST_POSTGRESQL"));
  }

  public static String driver() {
    return postgresql() ? "org.postgresql.Driver" : "org.opengauss.Driver";
  }

  public static org.springframework.jdbc.datasource.DriverManagerDataSource dataSource(
      boolean migration) {
    var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource();
    ds.setDriverClassName(driver());
    ds.setUrl(require(System.getenv("TRUST_TEST_DB_URL")));
    ds.setUsername(System.getenv(migration ? "TRUST_TEST_MIGRATE_USER" : "TRUST_DB_USER"));
    ds.setPassword(System.getenv(migration ? "TRUST_MIGRATE_PASSWORD" : "TRUST_DB_PASSWORD"));
    ds.setSchema("trust_data");
    return ds;
  }
}
