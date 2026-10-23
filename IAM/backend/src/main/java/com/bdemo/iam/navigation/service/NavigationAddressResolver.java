package com.bdemo.iam.navigation.service;

import com.bdemo.iam.navigation.model.NavigationAppEntity;
import java.net.URI;
import java.util.Locale;

/** Resolves browser addresses independently of reverse-proxy transport settings. No network calls. */
public final class NavigationAddressResolver {
  private NavigationAddressResolver() {}

  public static String resolve(NavigationAppEntity app) {
    if (app == null) return null;
    String domain = clean(app.getDomainName()).toLowerCase(Locale.ROOT);
    String ip = clean(app.getOriginIp());
    if (domain.isEmpty() && ip.isEmpty()) return null;
    String scheme;
    String host;
    Integer port;
    if (!domain.isEmpty()) {
      // A nonempty invalid domain is a configuration error, never a silent fallback.
      if (domain.length() > 253 || !domain.contains(".") || domain.matches("[0-9.]+")) invalid();
      for (String label : domain.split("\\.", -1)) {
        if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) invalid();
      }
      host = domain;
      scheme = scheme(app.getPublicScheme(), "https");
      // Origin ports are NEVER reused as public ports.
      port = app.getPublicPort();
    } else {
      String[] parts = ip.split("\\.", -1);
      if (parts.length != 4) invalid();
      for (String part : parts) {
        if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) invalid();
      }
      host = ip;
      scheme = scheme(app.getOriginScheme(), "http");
      port = app.getOriginPort();
      if (port == null) invalid();
    }
    if (port != null && (port < 1 || port > 65535)) invalid();
    String path = clean(app.getModulePath());
    if (path.isEmpty()) path = "/";
    if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\")
        || path.contains("%") || path.contains("?") || path.contains("#")
        || path.chars().anyMatch(c -> c <= 32 || c >= 127)) invalid();
    for (String part : path.split("/", -1)) {
      if (part.equals(".") || part.equals("..")) invalid();
    }
    if (!path.endsWith("/")) path += "/";
    boolean defaultPort = port == null || ("https".equals(scheme) && port == 443)
        || ("http".equals(scheme) && port == 80);
    String value = scheme + "://" + host + (defaultPort ? "" : ":" + port) + path;
    URI parsed = URI.create(value);
    if (parsed.getHost() == null || parsed.getUserInfo() != null) invalid();
    return value;
  }

  private static String scheme(String value, String fallback) {
    String result = clean(value).toLowerCase(Locale.ROOT);
    if (result.isEmpty()) result = fallback;
    if (!result.equals("http") && !result.equals("https")) invalid();
    return result;
  }
  private static String clean(String value) { return value == null ? "" : value.trim(); }
  private static void invalid() { throw new IllegalArgumentException("Invalid navigation address configuration"); }
}
