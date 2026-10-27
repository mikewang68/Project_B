package com.mt.wms.auth;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;

/** One fail-closed policy shared by local and IAM-backed accounts. */
public final class WmsReadOnlyPolicy {
    private static final Set<String> ROLES = Set.of(
            "AUDITOR", "READONLY", "ROLE_READONLY", "READONLY_AUDITOR", "PLATFORM_AUDITOR_READONLY");

    private WmsReadOnlyPolicy() {}

    public static boolean hasReadOnlyRole(Collection<String> roles) {
        return roles != null && roles.stream().anyMatch(role -> role != null
                && ROLES.contains(role.toUpperCase(Locale.ROOT)));
    }

    public static boolean denies(String method, String path) {
        // Account self-maintenance is not a business write. CSRF and authentication still apply.
        if (("POST".equals(method) && "/api/v1/auth/logout".equals(path))
                || ("PUT".equals(method) && "/api/v1/auth/password".equals(path))) return false;
        return !Set.of("GET", "HEAD", "OPTIONS").contains(method)
                || path.toLowerCase(Locale.ROOT).matches(".*/(export|download)(/.*)?");
    }

    public static String requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && path.startsWith(context + "/")
                ? path.substring(context.length()) : path;
    }
}
