package com.bproject.trust.wallet;

import com.bproject.trust.ports.SignerPolicyReader;
import com.bproject.trust.shared.web.ApiError;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WalletAuthorizationService {
  private final JdbcTemplate db;
  private final WalletService wallets;
  private final SignerPolicyReader reader;
  public WalletAuthorizationService(JdbcTemplate db, WalletService wallets, SignerPolicyReader reader) {
    this.db=db; this.wallets=wallets; this.reader=reader;
  }
  public Map<String,Object> status(String org,String walletId,String source,String eventType) {
    if (source==null || !source.matches("[A-Za-z0-9._-]{1,80}")
        || eventType==null || !eventType.matches("[A-Z_]{1,40}"))
      throw new ApiError(400,"来源或事件类型格式无效");
    var wallet=wallets.wallet(walletId,org);
    var bindings=db.queryForList("SELECT wallet_id FROM wallet_bindings WHERE org_id=? AND source_system=?",org,source);
    if (bindings.isEmpty() || !walletId.equals(bindings.get(0).get("wallet_id")))
      throw new ApiError(404,"当前来源未绑定此钱包或无权访问");
    var observations=reader.read(org,source,(String)wallet.get("fingerprint"));
    String result=classify(observations,org,source,(String)wallet.get("fingerprint"),(String)wallet.get("msp_id"),eventType);
    return Map.of("walletId",walletId,"sourceSystem",source,"fingerprint",wallet.get("fingerprint"),
        "eventType",eventType,"status",result,"checkedAt",Instant.now().toString(),
        "walletState",wallet.get("state"),"observations",observations.stream().map(o -> Map.of(
            "organization",o.organization(),"available",o.available())).toList());
  }
  static String classify(List<SignerPolicyReader.Observation> observations,String org,String source,String fingerprint,String msp,String type) {
    if (observations.size()<2 || observations.stream().map(SignerPolicyReader.Observation::organization).distinct().count()!=observations.size()
        || observations.stream().anyMatch(o -> !o.available())) return "UNKNOWN";
    var first=observations.get(0).policy();
    if (observations.stream().anyMatch(o -> !Objects.equals(first,o.policy()))) return "INCONSISTENT";
    if (first==null) return "UNAUTHORIZED";
    if (!org.equals(first.get("orgId")) || !source.equals(first.get("sourceSystem"))
        || !fingerprint.equals(first.get("fingerprint")) || !(first.get("enabled") instanceof Boolean)
        || !(first.get("mspId") instanceof String)) return "UNKNOWN";
    Object configured=first.get("eventTypes");
    List<?> types=configured==null?List.of("WAREHOUSE_IN","DISPATCH"):
        configured instanceof List<?> values?values:null;
    if (types==null || types.size()>80 || types.stream().anyMatch(t -> !(t instanceof String s) || !s.matches("[A-Z_]{1,40}"))) return "UNKNOWN";
    return Boolean.TRUE.equals(first.get("enabled")) && msp.equals(first.get("mspId")) && types.contains(type)?"AUTHORIZED":"UNAUTHORIZED";
  }
}
