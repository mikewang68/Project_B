package com.mt.wms.masterdata;

import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/sequences")
class BusinessSequenceController {
    private final BusinessSequenceService service; BusinessSequenceController(BusinessSequenceService service){this.service=service;}
    @PostMapping("/{prefix}/next") @PreAuthorize("hasAuthority('master:write')")
    ApiResponse<String> next(@PathVariable String prefix,Authentication auth,HttpSession session,HttpServletRequest request){return ApiResponse.ok(service.next(prefix,(WmsPrincipal)auth.getPrincipal(),session),request.getAttribute(RequestIdFilter.ATTRIBUTE).toString());}
}
