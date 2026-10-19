package com.bproject.trust.ingress;

import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.wallet.WalletKeyStore;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Explicit read-only adapters for the operator tool; intentionally no LedgerGateway submit API. */
final class ReadOnlyVerificationResources implements GenericEvidenceVerify.Resources {
  private final GenericEvidenceVerify.Target target;
  private final Connection connection;
  private final JdbcTemplate db;
  private Gateway gateway;
  private ManagedChannel channel;
  private final EvidenceStorage storage;

  ReadOnlyVerificationResources(
      GenericEvidenceVerify.Target target, GenericEvidenceVerify.Credentials credentials)
      throws Exception {
    this.target = target;
    var instance = target.instance();
    var ds =
        new DriverManagerDataSource(
            instance.databaseUrl(), instance.appRole(), credentials.databasePassword());
    ds.setDriverClassName("org.opengauss.Driver");
    var properties = new Properties();
    properties.setProperty("currentSchema", "trust_data");
    properties.setProperty("connectTimeout", "10");
    properties.setProperty("socketTimeout", "30");
    ds.setConnectionProperties(properties);
    connection = ds.getConnection();
    try {
      configureSnapshot(connection);
      db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      db.setQueryTimeout(15);
      db.setMaxRows(2);
      storage = new ReadOnlyIpfs(target.ipfsUrl());
    } catch (Exception e) {
      connection.close();
      throw e;
    }
  }

  static void configureSnapshot(Connection connection) throws Exception {
    connection.setReadOnly(true);
    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    connection.setAutoCommit(false);
    if (!connection.isReadOnly()
        || connection.getAutoCommit()
        || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ)
      throw new GenericEvidenceVerify.Failure("READ_ONLY_SNAPSHOT_REQUIRED");
  }

  public JdbcTemplate db() {
    return db;
  }

  public EvidenceStorage storage() {
    return storage;
  }

  public Map<String, Object> find(String org, String id) throws Exception {
    if (gateway == null) {
      Path root = GenericEvidenceVerify.absolute(target.instance().root());
      Path tls = root.resolve("runtime/secrets/fabric/tls-ca.crt");
      Path key = root.resolve("runtime/secrets/wallets").resolve(target.instance().queryKeyRef());
      for (Path path :
          List.of(
              tls,
              key.resolve("certificate.pem"),
              key.resolve("private-key.enc"),
              root.resolve("runtime/secrets/wallets/master.key")))
        GenericEvidenceVerify.safeExisting(path);
      var material = new WalletKeyStore(root.toString()).load(target.instance().queryKeyRef());
      checkQueryFingerprint(target, material.fingerprint());
      material.certificate().checkValidity();
      channel =
          NettyChannelBuilder.forTarget(target.fabricEndpoint())
              .proxyDetector(address -> null)
              .maxInboundMessageSize(1048576)
              .sslContext(GrpcSslContexts.forClient().trustManager(tls.toFile()).build())
              .overrideAuthority(target.serverName())
              .build();
      gateway =
          Gateway.newInstance()
              .identity(new X509Identity(target.queryMsp(), material.certificate()))
              .signer(Signers.newPrivateKeySigner(material.key()))
              .connection(channel)
              .evaluateOptions(o -> o.withDeadlineAfter(10, TimeUnit.SECONDS))
              .connect();
    }
    byte[] bytes =
        gateway
            .getNetwork(target.instance().channel())
            .getContract(target.contract())
            .evaluateTransaction("GetEvent", org, id);
    String text = new String(bytes, StandardCharsets.UTF_8);
    return "null".equals(text) ? null : Json.map(text);
  }

  @Override
  public void close() throws Exception {
    try {
      if (gateway != null) gateway.close();
    } finally {
      if (channel != null) channel.shutdownNow();
      try {
        connection.rollback();
      } finally {
        connection.close();
      }
    }
  }

  static void checkQueryFingerprint(GenericEvidenceVerify.Target target, String fingerprint) {
    if (!target.queryFingerprint().equals(fingerprint))
      throw new GenericEvidenceVerify.Failure("QUERY_IDENTITY_CHANGED");
  }

  static final class ReadOnlyIpfs implements EvidenceStorage {
    private final String url;
    private final HttpClient http;

    ReadOnlyIpfs(String url) {
      this.url = url;
      http =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(5))
              .followRedirects(HttpClient.Redirect.NEVER)
              .proxy(
                  new ProxySelector() {
                    public List<Proxy> select(URI uri) {
                      return List.of(Proxy.NO_PROXY);
                    }

                    public void connectFailed(URI uri, SocketAddress sa, java.io.IOException e) {}
                  })
              .build();
    }

    public long usedBytes() {
      throw new UnsupportedOperationException("Read-only evidence lookup");
    }

    public String add(byte[] bytes) {
      throw new UnsupportedOperationException("Read-only evidence lookup");
    }

    public byte[] cat(String cid) throws Exception {
      if (cid == null || !cid.matches("[A-Za-z0-9]{20,120}"))
        throw new GenericEvidenceVerify.Failure("INVALID_CID");
      var request =
          HttpRequest.newBuilder(URI.create(url + "/api/v0/cat?arg=" + cid))
              .timeout(Duration.ofSeconds(30))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      var pending = http.sendAsync(request, info -> new LimitedBody(1048576));
      try {
        var response = pending.get(35, TimeUnit.SECONDS);
        if (response.statusCode() != 200)
          throw new GenericEvidenceVerify.Failure("ARCHIVE_UNAVAILABLE");
        return response.body();
      } finally {
        pending.cancel(true);
      }
    }
  }

  static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedBody(int limit) {
      this.limit = limit;
    }

    public CompletionStage<byte[]> getBody() {
      return result;
    }

    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      result.whenComplete(
          (value, error) -> {
            if (error != null) s.cancel();
          });
      s.request(Long.MAX_VALUE);
    }

    public void onNext(List<ByteBuffer> chunks) {
      if (result.isDone()) return;
      for (var chunk : chunks) {
        if (chunk.remaining() > limit - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(new GenericEvidenceVerify.Failure("ARCHIVE_TOO_LARGE"));
          return;
        }
        byte[] part = new byte[chunk.remaining()];
        chunk.get(part);
        bytes.writeBytes(part);
      }
    }

    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
