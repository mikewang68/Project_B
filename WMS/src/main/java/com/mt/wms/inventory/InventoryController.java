package com.mt.wms.inventory;

import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory")
class InventoryController {
    private final InventoryService service;
    InventoryController(InventoryService service) { this.service = service; }

    @GetMapping("/balances") @PreAuthorize("hasAuthority('inventory:read')")
    ApiResponse<List<InventoryModels.BalanceView>> balances(Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.balances(principal(auth), session), id(request));
    }
    @GetMapping("/transactions") @PreAuthorize("hasAuthority('inventory:read')")
    ApiResponse<List<InventoryModels.TransactionView>> transactions(Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.transactions(principal(auth), session), id(request));
    }
    @GetMapping("/warnings") @PreAuthorize("hasAuthority('inventory:read')")
    ApiResponse<List<InventoryModels.WarningView>> warnings(Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.warnings(principal(auth), session), id(request));
    }
    @GetMapping("/serials") @PreAuthorize("hasAuthority('inventory:read')")
    ApiResponse<List<InventoryModels.SerialView>> serials(Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.serials(principal(auth), session), id(request));
    }
    @GetMapping("/replenishment-rules") @PreAuthorize("hasAuthority('inventory:read')")
    ApiResponse<List<InventoryModels.ReplenishmentRuleView>> replenishmentRules(Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.replenishmentRules(principal(auth), session), id(request));
    }
    @PostMapping("/adjustments") @PreAuthorize("hasAuthority('inventory:write')")
    ApiResponse<InventoryModels.OperationResult> adjust(@Valid @RequestBody InventoryModels.AdjustmentRequest body, Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.adjust(body, principal(auth), session), id(request));
    }
    @PostMapping("/moves") @PreAuthorize("hasAuthority('inventory:write')")
    ApiResponse<InventoryModels.MoveResult> move(@Valid @RequestBody InventoryModels.MoveRequest body, Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.move(body, principal(auth), session), id(request));
    }
    @PostMapping("/freeze") @PreAuthorize("hasAuthority('inventory:write')")
    ApiResponse<InventoryModels.OperationResult> freeze(@Valid @RequestBody InventoryModels.QuantityRequest body, Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.freeze(body, principal(auth), session), id(request));
    }
    @PostMapping("/unfreeze") @PreAuthorize("hasAuthority('inventory:write')")
    ApiResponse<InventoryModels.OperationResult> unfreeze(@Valid @RequestBody InventoryModels.QuantityRequest body, Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.unfreeze(body, principal(auth), session), id(request));
    }
    @PostMapping("/counts") @PreAuthorize("hasAuthority('inventory:write')")
    ApiResponse<InventoryModels.OperationResult> count(@Valid @RequestBody InventoryModels.CountRequest body, Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.count(body, principal(auth), session), id(request));
    }
    @PostMapping("/serials") @PreAuthorize("hasAuthority('inventory:write')")
    ApiResponse<InventoryModels.SerialView> createSerial(@Valid @RequestBody InventoryModels.SerialRequest body, Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.createSerial(body, principal(auth), session), id(request));
    }
    @PostMapping("/replenishment-rules") @PreAuthorize("hasAuthority('inventory:write')")
    ApiResponse<InventoryModels.ReplenishmentRuleView> createReplenishmentRule(@Valid @RequestBody InventoryModels.ReplenishmentRuleRequest body, Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.saveReplenishmentRule(null, body, principal(auth), session), id(request));
    }
    @PutMapping("/replenishment-rules/{ruleId}") @PreAuthorize("hasAuthority('inventory:write')")
    ApiResponse<InventoryModels.ReplenishmentRuleView> updateReplenishmentRule(@PathVariable long ruleId, @Valid @RequestBody InventoryModels.ReplenishmentRuleRequest body, Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.saveReplenishmentRule(ruleId, body, principal(auth), session), id(request));
    }
    @PostMapping("/replenishments/generate") @PreAuthorize("hasAuthority('inventory:write')")
    ApiResponse<InventoryModels.ReplenishmentResult> generateReplenishment(@Valid @RequestBody InventoryModels.ReplenishmentGenerateRequest body, Authentication auth, HttpSession session, HttpServletRequest request) {
        return ApiResponse.ok(service.generateReplenishment(body, principal(auth), session), id(request));
    }

    private WmsPrincipal principal(Authentication auth) { return (WmsPrincipal) auth.getPrincipal(); }
    private String id(HttpServletRequest request) { return request.getAttribute(RequestIdFilter.ATTRIBUTE).toString(); }
}
