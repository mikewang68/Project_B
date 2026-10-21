package com.bproject.trust.adapters.fabric;

import com.bproject.trust.ports.SignerPolicyReader;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.wallet.WalletKeyStore;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.naming.ldap.LdapName;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GatewaySignerPolicyReader implements SignerPolicyReader {
  private final WalletKeyStore keys;
  private final String root, endpoint, serverName, channelName, contractName, keyRef, msp;
  private final List<String> organizations;

  public GatewaySignerPolicyReader(WalletKeyStore keys,
      @Value("${trust.root}") String root,
      @Value("${trust.fabric-endpoint}") String endpoint,
      @Value("${trust.fabric-server-name}") String serverName,
      @Value("${trust.fabric-channel}") String channelName,
      @Value("${trust.fabric-contract}") String contractName,
      @Value("${trust.fabric-query-key-ref:}") String keyRef,
      @Value("${trust.signer-query-msp:Org1MSP}") String msp,
      @Value("${trust.signer-query-organizations:}") String organizations) {
    this.keys=keys; this.root=root; this.endpoint=endpoint; this.serverName=serverName;
    this.channelName=channelName; this.contractName=contractName; this.keyRef=keyRef; this.msp=msp;
    this.organizations=Arrays.stream(organizations.split(",")).map(String::trim)
        .filter(s -> !s.isEmpty()).distinct().toList();
  }

  public List<Observation> read(String org, String source, String fingerprint) {
    // No legacy identity fallback. Incomplete coverage must never look authorized.
    if (keyRef.isBlank() || organizations.size()<2 || organizations.size()>8
        || organizations.stream().anyMatch(s -> !s.matches("[A-Za-z0-9._-]{1,80}")))
      return List.of();
    List<Observation> results=new ArrayList<>();
    try {
      var material=keys.load(keyRef);
      material.certificate().checkValidity();
      for (var rdn : new LdapName(material.certificate().getSubjectX500Principal().getName()).getRdns())
        if (rdn.getType().equalsIgnoreCase("CN") && rdn.getValue().toString().toLowerCase(Locale.ROOT).startsWith("admin"))
          return List.of();
      var channel=NettyChannelBuilder.forTarget(endpoint).proxyDetector(a -> null)
          .maxInboundMessageSize(65536)
          .sslContext(GrpcSslContexts.forClient().trustManager(Path.of(root,"runtime/secrets/fabric/tls-ca.crt").toFile()).build())
          .overrideAuthority(serverName).build();
      try (var gateway=Gateway.newInstance().identity(new X509Identity(msp,material.certificate()))
          .signer(Signers.newPrivateKeySigner(material.key())).connection(channel)
          .evaluateOptions(o -> o.withDeadlineAfter(5,TimeUnit.SECONDS)).connect()) {
        var contract=gateway.getNetwork(channelName).getContract(contractName);
        for (String organization:organizations) {
          try {
            // Gateway SDK maps this to EvaluateRequest.targetOrganizations: distinct orgs,
            // not two evaluations against an unconstrained gateway-selected peer.
            byte[] raw=contract.newProposal("GetSigner").addArguments(org,source,fingerprint)
                .setEndorsingOrganizations(organization).build().evaluate();
            String value=new String(raw,StandardCharsets.UTF_8);
            results.add(new Observation(organization,true,"null".equals(value)?null:Json.map(value)));
          } catch (Exception unavailable) { results.add(new Observation(organization,false,null)); }
        }
      } finally { channel.shutdownNow(); }
    } catch (Exception unavailable) { return List.of(); }
    return results;
  }
}
