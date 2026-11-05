package com.mt.wms.stockin;

import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stockin/psms-tasks")
class PsmsInboundController {
    private final PsmsInboundService service;
    PsmsInboundController(PsmsInboundService service){this.service=service;}
    @GetMapping @PreAuthorize("hasAuthority('stockin:read')")
    ApiResponse<List<PsmsInboundModels.TaskView>> list(Authentication auth,HttpSession session,HttpServletRequest req){return ApiResponse.ok(service.list((WmsPrincipal)auth.getPrincipal(),session),trace(req));}
    @GetMapping("/{taskId}") @PreAuthorize("hasAuthority('stockin:read')")
    ApiResponse<PsmsInboundModels.TaskView> get(@PathVariable String taskId,Authentication auth,HttpSession session,HttpServletRequest req){return ApiResponse.ok(service.get(taskId,(WmsPrincipal)auth.getPrincipal(),session),trace(req));}
    @PostMapping("/{taskId}/confirm") @PreAuthorize("hasAuthority('stockin:write')")
    ApiResponse<PsmsInboundModels.TaskView> confirm(@PathVariable String taskId,@Valid @RequestBody PsmsInboundModels.ConfirmRequest body,Authentication auth,HttpSession session,HttpServletRequest req){return ApiResponse.ok(service.confirm(taskId,body,(WmsPrincipal)auth.getPrincipal(),session),trace(req));}
    @PostMapping("/{taskId}/retry-delivery") @PreAuthorize("hasAuthority('stockin:write')")
    ApiResponse<PsmsInboundModels.TaskView> retry(@PathVariable String taskId,Authentication auth,HttpSession session,HttpServletRequest req){return ApiResponse.ok(service.retryDelivery(taskId,(WmsPrincipal)auth.getPrincipal(),session),trace(req));}
    private String trace(HttpServletRequest req){return String.valueOf(req.getAttribute(RequestIdFilter.ATTRIBUTE));}
}
