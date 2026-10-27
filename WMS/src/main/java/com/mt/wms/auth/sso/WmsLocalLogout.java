package com.mt.wms.auth.sso;

import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** Local logout must not depend on the availability of the central identity service. */
public final class WmsLocalLogout {
    private WmsLocalLogout() {}

    public static boolean clear(HttpServletRequest request, HttpServletResponse response) {
        boolean complete = true;
        var session = request.getSession(false);
        if (session != null) {
            String id = session.getId();
            WmsSsoLogoutRegistry.revokeLocalSession(id);
            try {
                session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                session.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // A concurrent logout already destroyed it.
            } catch (RuntimeException failure) {
                complete = false;
            } finally {
                WmsSsoLogoutRegistry.unbind(id);
            }
        }
        SecurityContextHolder.clearContext();
        SessionCookieConfig config = request.getServletContext().getSessionCookieConfig();
        String name = config.getName() == null ? "JSESSIONID" : config.getName();
        String path = config.getPath() == null || config.getPath().isBlank()
                ? (request.getContextPath().isBlank() ? "/" : request.getContextPath()) : config.getPath();
        var cookie = ResponseCookie.from(name, "").maxAge(0).path(path).httpOnly(true)
                .secure(config.isSecure() || request.isSecure()).sameSite("Lax");
        if (config.getDomain() != null && !config.getDomain().isBlank()) cookie.domain(config.getDomain());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("XSRF-TOKEN", "")
                .maxAge(0).path("/").secure(request.isSecure()).sameSite("Lax").build().toString());
        if (complete) response.setHeader("X-Wms-Local-Logout", "complete");
        return complete;
    }
}
