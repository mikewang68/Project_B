package com.bproject.trust.wallet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bproject.trust.ports.SignerPolicyReader;
import com.bproject.trust.shared.web.ApiError;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WalletAuthorizationTest {
  Map<String,Object> policy() {
    return new HashMap<>(Map.of("orgId","tenant","sourceSystem","SIM","fingerprint","fingerprint",
        "mspId","Org1MSP","enabled",true,"revision",1,"eventTypes",List.of("SIM_EVENT")));
  }
  String classify(Map<String,Object> a,Map<String,Object> b) {
    return WalletAuthorizationService.classify(List.of(new SignerPolicyReader.Observation("Org1MSP",true,a),
        new SignerPolicyReader.Observation("Org2MSP",true,b)),"tenant","SIM","fingerprint","Org1MSP","SIM_EVENT");
  }
  @Test void requiresCompleteIndependentCoverage() {
    var p=policy();
    assertEquals("UNKNOWN",WalletAuthorizationService.classify(List.of(),"tenant","SIM","fingerprint","Org1MSP","SIM_EVENT"));
    assertEquals("UNKNOWN",WalletAuthorizationService.classify(List.of(new SignerPolicyReader.Observation("Org1MSP",true,p),
        new SignerPolicyReader.Observation("Org1MSP",true,p)),"tenant","SIM","fingerprint","Org1MSP","SIM_EVENT"));
    assertEquals("UNKNOWN",WalletAuthorizationService.classify(List.of(new SignerPolicyReader.Observation("Org1MSP",true,p),
        new SignerPolicyReader.Observation("Org2MSP",false,null)),"tenant","SIM","fingerprint","Org1MSP","SIM_EVENT"));
  }
  @Test void nullDisabledAndEmptyTypesDeny() {
    assertEquals("UNAUTHORIZED",classify(null,null));
    var p=policy(); p.put("enabled",false); assertEquals("UNAUTHORIZED",classify(p,p));
    p.put("enabled",true); p.put("eventTypes",List.of()); assertEquals("UNAUTHORIZED",classify(p,p));
    p.remove("eventTypes"); assertEquals("UNAUTHORIZED",classify(p,p));
  }
  @Test void authorizesOnlyMatchingPolicyAndReportsDifferences() {
    var p=policy(); assertEquals("AUTHORIZED",classify(p,p));
    var changed=policy(); changed.put("revision",2); assertEquals("INCONSISTENT",classify(p,changed));
    assertEquals("INCONSISTENT",classify(p,null));
    p.put("orgId","other"); assertEquals("UNKNOWN",classify(p,p));
    p=policy(); p.put("eventTypes","SIM_EVENT"); assertEquals("UNKNOWN",classify(p,p));
    p=policy(); p.put("mspId","different"); assertEquals("UNAUTHORIZED",classify(p,p));
  }
  @Test void historicalMissingTypesOnlyAuthorizeLegacyKinds() {
    var p=policy(); p.remove("eventTypes");
    var observations=List.of(new SignerPolicyReader.Observation("Org1MSP",true,p),new SignerPolicyReader.Observation("Org2MSP",true,p));
    assertEquals("AUTHORIZED",WalletAuthorizationService.classify(observations,"tenant","SIM","fingerprint","Org1MSP","WAREHOUSE_IN"));
    assertEquals("UNAUTHORIZED",WalletAuthorizationService.classify(observations,"tenant","SIM","fingerprint","Org1MSP","ARRIVAL"));
  }
  @Test void requiresCurrentTenantBindingBeforeNetworkCall() {
    var db=mock(JdbcTemplate.class); var wallets=mock(WalletService.class); var reader=mock(SignerPolicyReader.class);
    when(wallets.wallet("wallet","tenant")).thenReturn(Map.of("fingerprint","fingerprint","msp_id","Org1MSP","state","ACTIVE"));
    when(db.queryForList(anyString(),eq("tenant"),eq("SIM"))).thenReturn(List.of(Map.of("wallet_id","other")));
    var service=new WalletAuthorizationService(db,wallets,reader);
    assertThrows(ApiError.class,()->service.status("tenant","wallet","SIM","SIM_EVENT"));
    verifyNoInteractions(reader);
    when(db.queryForList(anyString(),eq("tenant"),eq("SIM"))).thenReturn(List.of(Map.of("wallet_id","wallet")));
    when(reader.read("tenant","SIM","fingerprint")).thenReturn(List.of());
    var result=service.status("tenant","wallet","SIM","SIM_EVENT");
    assertEquals("UNKNOWN",result.get("status")); assertNotNull(result.get("checkedAt"));
    assertFalse(result.containsKey("key_ref"));
  }
}
