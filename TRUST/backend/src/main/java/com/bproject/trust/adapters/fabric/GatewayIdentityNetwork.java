package com.bproject.trust.adapters.fabric;

import com.bproject.trust.ports.IdentityNetwork;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.wallet.WalletKeyStore.Material;
import io.grpc.netty.shaded.io.grpc.netty.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.identity.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GatewayIdentityNetwork implements IdentityNetwork {
  private final String root, endpoint, serverName, channelName, contractName;

  public GatewayIdentityNetwork(
      @Value("${trust.root}") String root,
      @Value("${trust.fabric-endpoint}") String endpoint,
      @Value("${trust.fabric-server-name}") String serverName,
      @Value("${trust.fabric-channel}") String channelName,
      @Value("${trust.fabric-contract}") String contractName) {
    this.root = root;
    this.endpoint = endpoint;
    this.serverName = serverName;
    this.channelName = channelName;
    this.contractName = contractName;
  }

  @Override
  public Map<String, Object> verify(String mspId, String orgId, String userId, Material material)
      throws Exception {
    material.certificate().checkValidity();
    var channel =
        NettyChannelBuilder.forTarget(endpoint)
            .overrideAuthority(serverName)
            .sslContext(
                GrpcSslContexts.forClient()
                    .trustManager(Path.of(root, "runtime/secrets/fabric/tls-ca.crt").toFile())
                    .build())
            .build();
    try (var gateway =
        Gateway.newInstance()
            .connection(channel)
            .identity(new X509Identity(mspId, material.certificate()))
            .signer(Signers.newPrivateKeySigner(material.key()))
            .evaluateOptions(o -> o.withDeadlineAfter(8, TimeUnit.SECONDS))
            .connect()) {
      var bytes =
          gateway
              .getNetwork(channelName)
              .getContract(contractName)
              .evaluateTransaction("IdentityProbe");
      var result = Json.map(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      if (!mspId.equals(result.get("mspId"))
          || !orgId.equals(result.get("orgId"))
          || !userId.equals(result.get("userId")))
        throw new IllegalStateException("NETWORK_IDENTITY_MISMATCH");
      return Map.of(
          "channel",
          channelName,
          "contract",
          contractName,
          "fingerprint",
          material.fingerprint(),
          "checkedAt",
          java.time.Instant.now().toString(),
          "result",
          result,
          "method",
          "Gateway.evaluate/IdentityProbe");
    } finally {
      channel.shutdownNow();
    }
  }
}
