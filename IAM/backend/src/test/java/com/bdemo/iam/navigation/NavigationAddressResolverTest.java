package com.bdemo.iam.navigation;

import static org.junit.jupiter.api.Assertions.*;
import com.bdemo.iam.navigation.model.NavigationAppEntity;
import com.bdemo.iam.navigation.service.NavigationAddressResolver;
import org.junit.jupiter.api.Test;

class NavigationAddressResolverTest {
  private NavigationAppEntity configured() {
    NavigationAppEntity app = new NavigationAppEntity();
    app.setDomainName("dispatch.example.test");
    app.setPublicScheme("https");
    app.setOriginScheme("http");
    app.setOriginIp("192.0.2.74");
    app.setOriginPort(18443);
    app.setModulePath("/production-dispatch/");
    return app;
  }
  @Test void domainUsesPublicProtocolWithoutOriginPort() {
    assertEquals("https://dispatch.example.test/production-dispatch/", NavigationAddressResolver.resolve(configured()));
  }
  @Test void independentExplicitPublicPort() {
    var app = configured(); app.setPublicPort(8443);
    assertEquals("https://dispatch.example.test:8443/production-dispatch/", NavigationAddressResolver.resolve(app));
  }
  @Test void absentDomainUsesOriginProtocolIpPortAndPath() {
    var app = configured(); app.setDomainName(" "); app.setOriginScheme("https");
    assertEquals("https://192.0.2.74:18443/production-dispatch/", NavigationAddressResolver.resolve(app));
  }
  @Test void httpOriginAndDefaultPort() {
    var app = configured(); app.setDomainName(null); app.setOriginPort(80); app.setModulePath("/emcs");
    assertEquals("http://192.0.2.74/emcs/", NavigationAddressResolver.resolve(app));
  }
  @Test void unconfiguredKeepsExistingGatewayRouting() {
    assertNull(NavigationAddressResolver.resolve(new NavigationAppEntity()));
  }
  @Test void rejectsMalformedDomainsWithoutSilentIpFallback() {
    for (String domain : new String[]{"https://evil.test", "x.test:8443", "x.test/path", "user@x.test", "x..test", "x.test.", "-x.test", "192.0.2.1"}) {
      var app = configured(); app.setDomainName(domain);
      assertThrows(IllegalArgumentException.class, () -> NavigationAddressResolver.resolve(app), domain);
    }
  }
  @Test void rejectsUnsafePaths() {
    for (String path : new String[]{"//evil.test/", "/../x", "/%2e%2e/x", "/x?token=y", "/x#ticket=y", "/x\\evil", "/x\ny"}) {
      var app = configured(); app.setModulePath(path);
      assertThrows(IllegalArgumentException.class, () -> NavigationAddressResolver.resolve(app), path);
    }
  }
  @Test void rejectsInvalidFallbackAndPort() {
    var app = configured(); app.setDomainName(""); app.setOriginIp("192.0.2.999");
    assertThrows(IllegalArgumentException.class, () -> NavigationAddressResolver.resolve(app));
    app.setOriginIp("192.0.2.74"); app.setOriginPort(0);
    assertThrows(IllegalArgumentException.class, () -> NavigationAddressResolver.resolve(app));
    app.setOriginPort(null);
    assertThrows(IllegalArgumentException.class, () -> NavigationAddressResolver.resolve(app));
  }
  @Test void rejectsUnsafeProtocols() {
    var app = configured(); app.setPublicScheme("javascript");
    assertThrows(IllegalArgumentException.class, () -> NavigationAddressResolver.resolve(app));
  }
}
