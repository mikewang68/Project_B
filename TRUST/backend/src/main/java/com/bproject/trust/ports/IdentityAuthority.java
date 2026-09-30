package com.bproject.trust.ports;

import com.bproject.trust.provisioning.IdentityProviderSettings.Organization;
import com.bproject.trust.wallet.WalletKeyStore.Material;
import java.util.Map;

public interface IdentityAuthority {
  Material issue(
      Organization org, String enrollmentId, String keyRef, Map<String, String> attributes)
      throws Exception;

  void revoke(Organization org, String enrollmentId) throws Exception;
}
