package com.bproject.ehm.platform.security;

import com.bproject.ehm.platform.audit.adapter.in.web.IamAuditIdentityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Business signatures must come from the verified session, never editable JSON fields. */
public final class EhmOperationIdentity {
    private EhmOperationIdentity() {}
    public static String require(HttpServletRequest request) {
        var actor = IamAuditIdentityFilter.actor(request);
        if (actor == null || actor.getName() == null || actor.getName().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录后操作");
        if (IamAuditIdentityFilter.readOnly(request))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只读账号不能签署业务操作");
        return actor.getName();
    }
}
