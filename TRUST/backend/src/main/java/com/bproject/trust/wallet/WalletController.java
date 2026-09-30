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
public class WalletController {
  private final WalletService wallets;
  private final CurrentIdentity identity;

  public WalletController(WalletService wallets, CurrentIdentity identity) {
    this.wallets = wallets;
    this.identity = identity;
  }

  private void requireIam(Authentication a) {
    if (!(a.getPrincipal() instanceof PlatformPrincipal))
      throw new ApiError(403, "钱包管理需要 IAM 实名登录，开发快捷登录不可用");
  }

  @GetMapping
  @PreAuthorize("hasAuthority('trust:wallet:read')")
  public Map<String, Object> list(Authentication a) {
    requireIam(a);
    return wallets.list(identity.org(a));
  }

  public record Proposal(String action, WalletService.Change change, String reason) {}

  @PostMapping("/requests")
  @PreAuthorize("hasAuthority('trust:wallet:manage')")
  public Map<String, Object> propose(Authentication a, @RequestBody Proposal body) {
    requireIam(a);
    return wallets.propose(
        identity.org(a), identity.actor(a), body.action(), body.change(), body.reason());
  }

  public record Review(boolean approve) {}

  @GetMapping("/requests/{id}/policy")
  @PreAuthorize("hasAuthority('trust:wallet:read')")
  public Map<String, Object> policy(
      Authentication a, @PathVariable String id, @RequestParam int revision) {
    requireIam(a);
    return wallets.policy(identity.org(a), id, revision);
  }

  @PostMapping("/requests/{id}/review")
  @PreAuthorize("hasAuthority('trust:wallet:review')")
  public Map<String, Object> review(
      Authentication a, @PathVariable String id, @RequestBody Review body) {
    requireIam(a);
    return wallets.review(identity.org(a), identity.actor(a), id, body.approve());
  }
}
