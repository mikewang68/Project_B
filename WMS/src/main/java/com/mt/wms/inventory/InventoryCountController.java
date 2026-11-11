package com.mt.wms.inventory;

import static com.mt.wms.inventory.InventoryCountModels.*;

import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inventory")
class InventoryCountController {
    private final InventoryCountService service;

    InventoryCountController(InventoryCountService service) { this.service=service; }

    @GetMapping("/count-records") @PreAuthorize("hasAuthority('inventory:read')")
    ApiResponse<List<View>> list(@RequestParam(required=false) State state, @RequestParam(required=false) Long beforeId,
                                 @AuthenticationPrincipal WmsPrincipal user,HttpSession session,HttpServletRequest req) {
        return ApiResponse.ok(service.list(state,beforeId,user,session),trace(req));
    }

    @PostMapping("/count-records") @PreAuthorize("hasAuthority('inventory:write')")
    ApiResponse<View> submit(@Valid @RequestBody Submit input,@AuthenticationPrincipal WmsPrincipal user,
                             HttpSession session,HttpServletRequest req) {
        return ApiResponse.ok(service.submit(input,user,session),trace(req));
    }

    @PostMapping("/count-records/{id}/review") @PreAuthorize("hasAuthority('inventory:write')")
    ApiResponse<View> review(@PathVariable long id,@Valid @RequestBody Review input,@AuthenticationPrincipal WmsPrincipal user,
                             HttpSession session,HttpServletRequest req) {
        return ApiResponse.ok(service.review(id,input,user,session),trace(req));
    }

    @PostMapping("/counts") @PreAuthorize("hasAuthority('inventory:write')")
    ApiResponse<InventoryModels.OperationResult> legacy(@Valid @RequestBody InventoryModels.CountRequest input,
                             @AuthenticationPrincipal WmsPrincipal user,HttpSession session,HttpServletRequest req) {
        return ApiResponse.ok(service.legacy(input,user,session),trace(req));
    }

    private String trace(HttpServletRequest req) { return String.valueOf(req.getAttribute(RequestIdFilter.ATTRIBUTE)); }
}
