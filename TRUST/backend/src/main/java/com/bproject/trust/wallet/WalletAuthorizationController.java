package com.bproject.trust.wallet;

import com.bproject.trust.identity.CurrentIdentity;
import com.bproject.trust.identity.PlatformPrincipal;
import com.bproject.trust.shared.web.ApiError;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletAuthorizationController {
  private final WalletAuthorizationService service;
  private final CurrentIdentity identity;
  public WalletAuthorizationController(WalletAuthorizationService service,CurrentIdentity identity) {
    this.service=service; this.identity=identity;
  }
  @GetMapping("/{id}/chain-authorization")
  @PreAuthorize("hasAuthority('trust:wallet:read')")
  public Map<String,Object> status(Authentication authentication,@PathVariable String id,
      @RequestParam String sourceSystem,@RequestParam String eventType) {
    if (!(authentication.getPrincipal() instanceof PlatformPrincipal)) throw new ApiError(403,"钱包管理需要 IAM 实名登录");
    return service.status(identity.org(authentication),id,sourceSystem,eventType);
  }
}
