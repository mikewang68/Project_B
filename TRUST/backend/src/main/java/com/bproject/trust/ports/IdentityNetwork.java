package com.bproject.trust.ports;

import com.bproject.trust.wallet.WalletKeyStore.Material;
import java.util.Map;

public interface IdentityNetwork {
  Map<String, Object> verify(String mspId, String orgId, String userId, Material material)
      throws Exception;
}
