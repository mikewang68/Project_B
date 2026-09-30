package com.bproject.trust.adapters.fabric;

import com.bproject.trust.ports.LedgerGateway;
import com.bproject.trust.shared.json.Json;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FabricClient implements LedgerGateway {
  private final Gateway gateway;
  private final ManagedChannel channel;
  private final Contract contract;
  private final String channelName, contractName;
  private final com.bproject.trust.wallet.WalletService wallets;
  private final com.bproject.trust.wallet.WalletKeyStore keys;

  public FabricClient(
      @Value("${trust.root}") String root,
      @Value("${trust.fabric-endpoint}") String endpoint,
      @Value("${trust.fabric-server-name}") String serverName,
      @Value("${trust.fabric-channel}") String channelName,
      @Value("${trust.fabric-contract}") String contractName,
      @Value("${trust.fabric-query-key-ref:}") String queryKeyRef,
      com.bproject.trust.wallet.WalletService wallets,
      com.bproject.trust.wallet.WalletKeyStore keys)
      throws Exception {
    this.wallets = wallets;
    this.keys = keys;
    this.channelName = channelName;
    this.contractName = contractName;
    Path p = Path.of(root, "runtime/secrets/fabric");
    channel =
        NettyChannelBuilder.forTarget(endpoint)
            .sslContext(
                GrpcSslContexts.forClient().trustManager(p.resolve("tls-ca.crt").toFile()).build())
            .overrideAuthority(serverName)
            .build();
    X509Identity queryIdentity;
    org.hyperledger.fabric.client.identity.Signer querySigner;
    if (!queryKeyRef.isBlank()) {
      var material = keys.load(queryKeyRef);
      queryIdentity = new X509Identity("Org1MSP", material.certificate());
      querySigner = Signers.newPrivateKeySigner(material.key());
    } else {
      // Compatibility for the existing demonstration deployment only.
      try (var cert = Files.newBufferedReader(p.resolve("user.crt"));
          var key = Files.newBufferedReader(p.resolve("user.key"))) {
        queryIdentity = new X509Identity("Org1MSP", Identities.readX509Certificate(cert));
        querySigner = Signers.newPrivateKeySigner(Identities.readPrivateKey(key));
      }
    }
      gateway =
          Gateway.newInstance()
              .identity(queryIdentity)
              .signer(querySigner)
              .connection(channel)
              .evaluateOptions(o -> o.withDeadlineAfter(8, TimeUnit.SECONDS))
              .endorseOptions(o -> o.withDeadlineAfter(20, TimeUnit.SECONDS))
              .submitOptions(o -> o.withDeadlineAfter(10, TimeUnit.SECONDS))
              .commitStatusOptions(o -> o.withDeadlineAfter(20, TimeUnit.SECONDS))
              .connect();
    contract = gateway.getNetwork(channelName).getContract(contractName);
  }

  public Map<String, Object> find(String org, String id) throws Exception {
    byte[] bytes = contract.evaluateTransaction("GetEvent", org, id);
    String s = new String(bytes, StandardCharsets.UTF_8);
    return s.equals("null") ? null : Json.map(s);
  }

  public Map<String, Object> submit(Map<String, Object> record, Consumer<String> prepared)
      throws Exception {
    if (record.containsKey("walletId")) {
      var wallet = wallets.active((String) record.get("walletId"), (String) record.get("orgId"));
      var material = keys.load((String) wallet.get("key_ref"));
      if (!material.fingerprint().equals(record.get("signerFingerprint")))
        throw new IllegalStateException("签名证书版本不一致");
      try (var managed =
          Gateway.newInstance()
              .identity(new X509Identity((String) wallet.get("msp_id"), material.certificate()))
              .signer(Signers.newPrivateKeySigner(material.key()))
              .connection(channel)
              .evaluateOptions(o -> o.withDeadlineAfter(8, TimeUnit.SECONDS))
              .endorseOptions(o -> o.withDeadlineAfter(20, TimeUnit.SECONDS))
              .submitOptions(o -> o.withDeadlineAfter(10, TimeUnit.SECONDS))
              .commitStatusOptions(o -> o.withDeadlineAfter(20, TimeUnit.SECONDS))
              .connect()) {
        return submitWith(
            managed.getNetwork(channelName).getContract(contractName), record, prepared);
      }
    }
    return submitWith(contract, record, prepared);
  }

  private Map<String, Object> submitWith(
      Contract contract, Map<String, Object> record, Consumer<String> prepared) throws Exception {
    String fn = record.get("supersedesId").equals("") ? "RegisterEvent" : "AppendCorrection";
    var transaction = contract.newProposal(fn).addArguments(Json.write(record)).build().endorse();
    prepared.accept(transaction.getTransactionId());
    var submitted = transaction.submitAsync();
    var status = submitted.getStatus();
    if (!status.isSuccessful()) {
      String reason = "交易未有效提交：" + status.getCode();
      wallets.rejected((String) record.get("id"), transaction.getTransactionId(), status.getBlockNumber(), reason);
      throw new IllegalStateException(reason);
    }
    var found = find((String) record.get("orgId"), (String) record.get("id"));
    if (found == null) throw new IllegalStateException("提交后尚未查到链上记录");
    found.put("blockNumber", status.getBlockNumber());
    return found;
  }

  public boolean healthy() {
    try {
      find("B-PROJECT", "00000000-0000-0000-0000-000000000000");
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  @PreDestroy
  public void close() {
    gateway.close();
    channel.shutdownNow();
  }
}
