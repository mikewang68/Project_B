package com.bproject.trust.adapters.ipfs;

import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.shared.DatabaseMutex;
import com.bproject.trust.shared.http.BoundedBody;
import com.bproject.trust.shared.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class IpfsClient implements EvidenceStorage {
  private final String url;
  private final long budget;
  private final HttpClient http;
  private final JdbcTemplate db;
  private final Duration exchangeTimeout, addTimeout;
  private final int metadataLimit, contentLimit;
  private final ReentrantLock writeLock = new ReentrantLock(true);

  public IpfsClient(String url, long budget) {
    this(
        url,
        budget,
        null,
        Duration.ofSeconds(5),
        Duration.ofSeconds(30),
        Duration.ofSeconds(90),
        65536,
        33554432);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public IpfsClient(
      @Value("${trust.ipfs-url}") String url,
      @Value("${trust.storage-budget}") long budget,
      JdbcTemplate db,
      @Value("${trust.ipfs.connect-timeout:5s}") Duration connectTimeout,
      @Value("${trust.ipfs.exchange-timeout:30s}") Duration exchangeTimeout,
      @Value("${trust.ipfs.add-timeout:90s}") Duration addTimeout,
      @Value("${trust.ipfs.metadata-limit:65536}") int metadataLimit,
      @Value("${trust.ipfs.content-limit:33554432}") int contentLimit) {
    if (connectTimeout.isZero()
        || connectTimeout.isNegative()
        || exchangeTimeout.isZero()
        || exchangeTimeout.isNegative()
        || addTimeout.isZero()
        || addTimeout.isNegative()
        || metadataLimit < 1024
        || metadataLimit > 65536
        || contentLimit < 20971520
        || contentLimit > 33554432
        || budget < 1) throw new IllegalArgumentException("IPFS 限额配置无效");
    this.url = url;
    this.budget = budget;
    this.db = db;
    this.exchangeTimeout = exchangeTimeout;
    this.addTimeout = addTimeout;
    this.metadataLimit = metadataLimit;
    this.contentLimit = contentLimit;
    http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
  }

  byte[] call(String op, byte[] body, String contentType) throws Exception {
    return call(op, body, contentType, System.nanoTime() + exchangeTimeout.toNanos());
  }

  private byte[] call(String op, byte[] body, String contentType, long deadline) throws Exception {
    long nanos = Math.min(exchangeTimeout.toNanos(), deadline - System.nanoTime());
    if (nanos <= 0) throw new java.util.concurrent.TimeoutException("IPFS 总处理时限已到");
    var b =
        HttpRequest.newBuilder(URI.create(url + "/api/v0/" + op)).timeout(Duration.ofNanos(nanos));
    if (contentType != null) b.header("Content-Type", contentType);
    var pending =
        http.sendAsync(
            b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            info ->
                new BoundedBody(
                    info.statusCode() == 200 && op.startsWith("cat?")
                        ? contentLimit
                        : metadataLimit));
    try {
      var r = pending.get(nanos, TimeUnit.NANOSECONDS);
      if (r.statusCode() != 200)
        throw new java.io.IOException("IPFS 请求失败 (" + r.statusCode() + ")");
      return r.body();
    } finally {
      if (!pending.isDone()) pending.cancel(true);
    }
  }

  public Map<String, Object> stat() throws Exception {
    return Json.map(new String(call("repo/stat", new byte[0], null), StandardCharsets.UTF_8));
  }

  public long usedBytes() throws Exception {
    return ((Number) stat().get("RepoSize")).longValue();
  }

  public String add(byte[] bytes) throws Exception {
    if (bytes.length > contentLimit) throw new java.io.IOException("IPFS 内容超过大小上限");
    long deadline = System.nanoTime() + addTimeout.toNanos();
    if (!writeLock.tryLock(addTimeout.toNanos(), TimeUnit.NANOSECONDS))
      throw new java.util.concurrent.TimeoutException("IPFS 容量锁等待超时");
    try (var mutex = DatabaseMutex.acquire(db, "ipfs:primary", deadline)) {
      long used =
          ((Number)
                  Json.map(
                          new String(
                              call("repo/stat", new byte[0], null, deadline),
                              StandardCharsets.UTF_8))
                      .get("RepoSize"))
              .longValue();
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
                          "multipart/form-data; boundary=" + boundary,
                          deadline),
                      StandardCharsets.UTF_8)
                  .trim());
      String cid = (String) result.get("Hash");
      validateCid(cid);
      byte[] readBack = call("cat?arg=" + cid, new byte[0], null, deadline);
      if (readBack.length != bytes.length || !Json.sha(readBack).equals(Json.sha(bytes)))
        throw new java.io.IOException("IPFS 保存后取回校验失败");
      return cid;
    } finally {
      writeLock.unlock();
    }
  }

  private static void validateCid(String cid) {
    if (cid == null || !cid.matches("[A-Za-z0-9]{20,120}"))
      throw new IllegalArgumentException("CID 不正确");
  }

  public byte[] cat(String cid) throws Exception {
    validateCid(cid);
    return call("cat?arg=" + cid, new byte[0], null);
  }
}
