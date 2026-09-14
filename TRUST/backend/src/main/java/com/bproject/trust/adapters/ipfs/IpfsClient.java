package com.bproject.trust.adapters.ipfs;

import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.shared.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IpfsClient implements EvidenceStorage {
  private final String url;
  private final long budget;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public IpfsClient(
      @Value("${trust.ipfs-url}") String url, @Value("${trust.storage-budget}") long budget) {
    this.url = url;
    this.budget = budget;
  }

  byte[] call(String op, byte[] body, String contentType) throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create(url + "/api/v0/" + op)).timeout(Duration.ofSeconds(30));
    if (contentType != null) b.header("Content-Type", contentType);
    var r =
        http.send(
            b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
    if (r.statusCode() != 200) throw new java.io.IOException("IPFS 请求失败 (" + r.statusCode() + ")");
    return r.body();
  }

  public Map<String, Object> stat() throws Exception {
    return Json.map(new String(call("repo/stat", new byte[0], null), StandardCharsets.UTF_8));
  }

  public long usedBytes() throws Exception {
    return ((Number) stat().get("RepoSize")).longValue();
  }

  public synchronized String add(byte[] bytes) throws Exception {
    long used = ((Number) stat().get("RepoSize")).longValue();
    if (used + bytes.length * 2L > budget) throw new java.io.IOException("IPFS 开发容量预算不足，证据保留待办");
    String boundary = "trust" + UUID.randomUUID();
    var out = new java.io.ByteArrayOutputStream();
    out.write(
        ("--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"evidence\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    out.write(bytes);
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var result =
        Json.map(
            new String(
                    call(
                        "add?pin=true&cid-version=1&raw-leaves=true&progress=false",
                        out.toByteArray(),
                        "multipart/form-data; boundary=" + boundary),
                    StandardCharsets.UTF_8)
                .trim());
    String cid = (String) result.get("Hash");
    if (!Json.sha(cat(cid)).equals(Json.sha(bytes)))
      throw new java.io.IOException("IPFS 保存后取回校验失败");
    return cid;
  }

  public byte[] cat(String cid) throws Exception {
    if (cid == null || !cid.matches("[A-Za-z0-9]{20,120}"))
      throw new IllegalArgumentException("CID 不正确");
    return call("cat?arg=" + cid, new byte[0], null);
  }
}
