package com.bproject.trust.logevidence;

import com.bproject.trust.identity.PlatformPrincipal;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Persist request intent before controller execution; HTTP outcome is not a ledger outcome. */
public class OperationAuditFilter extends OncePerRequestFilter {
  private final LogOperations ops;

  public OperationAuditFilter(LogOperations ops) {
    this.ops = ops;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    String path = req.getRequestURI();
    String pageId = req.getHeader("X-TRUST-Page-Action-Id");
    if (auth == null || !auth.isAuthenticated()
        || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken
        || !path.startsWith("/api/v1/")
        || path.startsWith("/api/v1/integrations/")
        || path.startsWith("/api/v1/audit/page-actions")
        || path.equals("/api/v1/csrf")
        || path.equals("/api/v1/identity-mode")) {
      chain.doFilter(req, res);
      return;
    }
    PlatformPrincipal p;
    String trace = pageId == null ? UUID.randomUUID().toString() : pageId;
    String action =
        "GET".equals(req.getMethod()) ? "READ" : path.endsWith("/logout") ? "LOGOUT" : "EXECUTE";
    try {
      p = ops.pagePrincipal(auth);
      if (pageId != null) {
        ops.verifyPageAction(auth, pageId);
        req.setAttribute("TRUST_VERIFIED_PAGE_ACTION", pageId);
      }
      ops.enqueue(
          p.orgId(),
          p.userId(),
          action,
          "",
          "STARTED",
          "REQUEST",
          "",
          req.getMethod(),
          path,
          "",
          trace);
    } catch (Exception e) {
      try {
        ops.rejected(auth, req.getMethod(), path);
      } catch (Exception persistenceFailure) {
        error(res, persistenceFailure);
        return;
      }
      error(res, e);
      return;
    }
    try {
      chain.doFilter(req, res);
    } catch (Exception failure) {
      try {
        ops.enqueue(p.orgId(), p.userId(), action, "", "FAILED", "REQUEST", "",
            req.getMethod(), path, "Request execution or transfer failed; outcome may be partial", trace);
      } catch (Exception unavailable) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("AUDIT_REQUEST_OUTCOME_UNCONFIRMED");
      }
      if (res.isCommitted()) {
        if (failure instanceof IOException io) throw io;
        if (failure instanceof ServletException se) throw se;
        throw new ServletException(failure);
      }
      res.resetBuffer();
      error(res, failure);
      return;
    }
    // No asynchronous controllers currently use this filter. Never label a pending async
    // transfer successful; its durable STARTED record remains explicitly unconfirmed.
    if (req.isAsyncStarted()) return;
    try {
      String outcome =
          res.getStatus() == 401 || res.getStatus() == 403
              ? "DENIED"
              : res.getStatus() >= 400 ? "FAILED" : "SUCCESS";
      ops.enqueue(
          p.orgId(),
          p.userId(),
          action,
          "",
          outcome,
          "REQUEST",
          "",
          req.getMethod(),
          path,
          "HTTP outcome only",
          trace);
    } catch (Exception e) {
      // STARTED remains durable if outcome persistence fails: never manufacture a successful
      // result.
      if (!res.isCommitted()) {
        res.resetBuffer();
        error(res, e);
      } else {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("AUDIT_REQUEST_OUTCOME_UNCONFIRMED");
      }
    }
  }

  private static void error(HttpServletResponse res, Exception e) throws IOException {
    res.setStatus(e instanceof ApiError a ? a.status : 503);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter()
        .write(
            Json.write(
                Map.of(
                    "code",
                    "AUDIT_OPERATION_UNCONFIRMED",
                    "message",
                    "操作留痕未确认，请先查询实际业务状态，勿换号重复提交")));
  }
}
