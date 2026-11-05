package com.bproject.iam.context;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * Backend-only read client. Subject/session come from the verified local SSO
 * binding; browsers cannot choose credentials, destination, menu or SQL.
 */
public final class SharedDispatchClient {
  private final URI base;
  private final String audience, credential;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

  public SharedDispatchClient(String baseUrl, String audience, String credential, boolean loopbackHttp) {
    base = URI.create(baseUrl);
    boolean local = base.getHost() != null && Set.of("127.0.0.1", "localhost", "[::1]").contains(base.getHost());
    if (base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
        || base.getRawFragment() != null || !Set.of("", "/").contains(base.getRawPath())
        || !("https".equals(base.getScheme()) || loopbackHttp && local && "http".equals(base.getScheme())))
      throw new IllegalArgumentException("Shared provider requires HTTPS or explicit loopback HTTP");
    if (!Set.of("wms", "ehms", "scs").contains(audience) || credential == null || credential.length() < 32)
      throw new IllegalArgumentException("Shared service identity is incomplete");
    this.audience = audience;
    this.credential = credential;
  }

  public static final class Rejected extends RuntimeException {
    private final int status;
    public Rejected(int status) { super("Shared dispatch request unavailable or denied"); this.status = status; }
    public int status() { return status; }
  }

  public String read(String subject, String sessionId, String resource, String id, int page, int pageSize) {
    if (subject == null || !subject.matches("[a-fA-F0-9-]{32,36}") || sessionId == null
        || !sessionId.matches("[-_a-zA-Z0-9]{1,256}") || !Set.of("plans", "work-orders").contains(resource)
        || id != null && !id.matches("[-_a-zA-Z0-9]{1,128}")
        || page < 1 || page > 10000 || pageSize < 1 || pageSize > 100)
      throw new Rejected(400);
    String path = "/api/shared/dispatch/" + resource + (id == null ? "" : "/" + id)
        + "?page=" + page + "&pageSize=" + pageSize;
    var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(8))
        .header("X-Shared-Audience", audience).header("X-Shared-Subject", subject)
        .header("X-Shared-Session-Id", sessionId).header("X-SSO-Service-Token", credential)
        .header("Accept", "application/json").GET().build();
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        int status = response.statusCode();
        if (status != 200) throw new Rejected(Set.of(400, 401, 403, 404).contains(status) ? status : 503);
        byte[] body = stream.readNBytes(1_048_577);
        if (body.length > 1_048_576) throw new Rejected(503);
        return new String(body, StandardCharsets.UTF_8);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new Rejected(503);
    } catch (IOException unavailable) { throw new Rejected(503); }
  }
}
