package com.mt.wms.auth;

import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/system")
class UserAdminController {
    private final UserAdminService service;

    UserAdminController(UserAdminService service) { this.service = service; }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('system:user:read')")
    ApiResponse<List<UserAdminModels.UserView>> users(Authentication auth, HttpServletRequest request) {
        return ApiResponse.ok(service.list(principal(auth)), requestId(request));
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('system:user:read')")
    ApiResponse<List<UserAdminModels.RoleOption>> roles(HttpServletRequest request) {
        return ApiResponse.ok(service.roles(), requestId(request));
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('system:user:write')")
    ApiResponse<UserAdminModels.UserView> create(@Valid @RequestBody UserAdminModels.SaveUserRequest body,
                                                  Authentication auth, HttpServletRequest request) {
        return ApiResponse.ok(service.create(principal(auth), body), requestId(request));
    }

    @PutMapping("/users/{userId}")
    @PreAuthorize("hasAuthority('system:user:write')")
    ApiResponse<UserAdminModels.UserView> update(@PathVariable long userId,
                                                  @Valid @RequestBody UserAdminModels.SaveUserRequest body,
                                                  Authentication auth, HttpServletRequest request) {
        return ApiResponse.ok(service.update(principal(auth), userId, body), requestId(request));
    }

    private WmsPrincipal principal(Authentication auth) { return (WmsPrincipal) auth.getPrincipal(); }
    private String requestId(HttpServletRequest request) { return request.getAttribute(RequestIdFilter.ATTRIBUTE).toString(); }
}
