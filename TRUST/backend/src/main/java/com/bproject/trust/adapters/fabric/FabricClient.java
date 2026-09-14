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

  public FabricClient(
      @Value("${trust.root}") String root,
      @Value("${trust.fabric-endpoint}") String endpoint,
      @Value("${trust.fabric-server-name}") String serverName,
      @Value("${trust.fabric-channel}") String channelName,
      @Value("${trust.fabric-contract}") String contractName)
      throws Exception {
    Path p = Path.of(root, "runtime/secrets/fabric");
    channel =
        NettyChannelBuilder.forTarget(endpoint)
            .sslContext(
                GrpcSslContexts.forClient().trustManager(p.resolve("tls-ca.crt").toFile()).build())
            .overrideAuthority(serverName)
            .build();
    try (var cert = Files.newBufferedReader(p.resolve("user.crt"));
        var key = Files.newBufferedReader(p.resolve("user.key"))) {
      gateway =
          Gateway.newInstance()
              .identity(new X509Identity("Org1MSP", Identities.readX509Certificate(cert)))
              .signer(Signers.newPrivateKeySigner(Identities.readPrivateKey(key)))
              .connection(channel)
              .evaluateOptions(o -> o.withDeadlineAfter(8, TimeUnit.SECONDS))
              .endorseOptions(o -> o.withDeadlineAfter(20, TimeUnit.SECONDS))
              .submitOptions(o -> o.withDeadlineAfter(10, TimeUnit.SECONDS))
              .commitStatusOptions(o -> o.withDeadlineAfter(20, TimeUnit.SECONDS))
              .connect();
    }
    contract = gateway.getNetwork(channelName).getContract(contractName);
  }

  public Map<String, Object> find(String org, String id) throws Exception {
    byte[] bytes = contract.evaluateTransaction("GetEvent", org, id);
    String s = new String(bytes, StandardCharsets.UTF_8);
    return s.equals("null") ? null : Json.map(s);
  }

  public Map<String, Object> submit(Map<String, Object> record, Consumer<String> prepared)
      throws Exception {
    String fn = record.get("supersedesId").equals("") ? "RegisterEvent" : "AppendCorrection";
    var transaction = contract.newProposal(fn).addArguments(Json.write(record)).build().endorse();
    prepared.accept(transaction.getTransactionId());
    var submitted = transaction.submitAsync();
    var status = submitted.getStatus();
    if (!status.isSuccessful()) throw new IllegalStateException("交易未有效提交：" + status.getCode());
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
