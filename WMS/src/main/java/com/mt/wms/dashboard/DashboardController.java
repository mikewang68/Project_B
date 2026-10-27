package com.mt.wms.dashboard;

import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import com.mt.wms.auth.WmsPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('dashboard:view')")
    ApiResponse<DashboardSummary> summary(Authentication authentication, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(dashboardService.summary((WmsPrincipal) authentication.getPrincipal(), session), request.getAttribute(RequestIdFilter.ATTRIBUTE).toString());
    }
}
