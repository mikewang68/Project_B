package com.mt.wms.stockin;

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
@RequestMapping("/api/v1/stockin")
class StockinController {
    private final StockinService service;
    StockinController(StockinService service){this.service=service;}

    @GetMapping @PreAuthorize("hasAuthority('stockin:read')")
    ApiResponse<List<StockinModels.OrderSummary>> list(Authentication auth,HttpSession session,HttpServletRequest request){return ApiResponse.ok(service.list(principal(auth),session),id(request));}
    @GetMapping("/{orderId}") @PreAuthorize("hasAuthority('stockin:read')")
    ApiResponse<StockinModels.OrderDetail> detail(@PathVariable long orderId,Authentication auth,HttpSession session,HttpServletRequest request){return ApiResponse.ok(service.detail(orderId,principal(auth),session),id(request));}
    @GetMapping("/{orderId}/receipts") @PreAuthorize("hasAuthority('stockin:read')")
    ApiResponse<List<StockinModels.ReceiptView>> receipts(@PathVariable long orderId,Authentication auth,HttpSession session,HttpServletRequest request){return ApiResponse.ok(service.receipts(orderId,principal(auth),session),id(request));}
    @GetMapping("/location-suggestions") @PreAuthorize("hasAuthority('stockin:read')")
    ApiResponse<List<StockinModels.LocationSuggestion>> locationSuggestions(@RequestParam String goodCode,Authentication auth,HttpSession session,HttpServletRequest request){return ApiResponse.ok(service.locationSuggestions(goodCode,principal(auth),session),id(request));}
    @PostMapping @PreAuthorize("hasAuthority('stockin:write')")
    ApiResponse<StockinModels.OrderDetail> create(@Valid @RequestBody StockinModels.CreateRequest body,Authentication auth,HttpSession session,HttpServletRequest request){return ApiResponse.ok(service.create(body,principal(auth),session),id(request));}
    @PostMapping("/{orderId}/receipts") @PreAuthorize("hasAuthority('stockin:write')")
    ApiResponse<StockinModels.ReceiveResult> receive(@PathVariable long orderId,@Valid @RequestBody StockinModels.ReceiveRequest body,Authentication auth,HttpSession session,HttpServletRequest request){return ApiResponse.ok(service.receive(orderId,body,principal(auth),session),id(request));}
    @PostMapping("/{orderId}/scan") @PreAuthorize("hasAuthority('stockin:write')")
    ApiResponse<StockinModels.ReceiveResult> scan(@PathVariable long orderId,@Valid @RequestBody StockinModels.ScanRequest body,Authentication auth,HttpSession session,HttpServletRequest request){return ApiResponse.ok(service.scan(orderId,body,principal(auth),session),id(request));}
    @PostMapping("/{orderId}/complete") @PreAuthorize("hasAuthority('stockin:write')")
    ApiResponse<StockinModels.OrderDetail> complete(@PathVariable long orderId,@Valid @RequestBody StockinModels.ActionRequest body,Authentication auth,HttpSession session,HttpServletRequest request){return ApiResponse.ok(service.complete(orderId,body,principal(auth),session),id(request));}
    @PostMapping("/{orderId}/cancel") @PreAuthorize("hasAuthority('stockin:write')")
    ApiResponse<StockinModels.OrderDetail> cancel(@PathVariable long orderId,@Valid @RequestBody StockinModels.ActionRequest body,Authentication auth,HttpSession session,HttpServletRequest request){return ApiResponse.ok(service.cancel(orderId,body,principal(auth),session),id(request));}
    @PostMapping("/quick") @PreAuthorize("hasAuthority('stockin:write')")
    ApiResponse<StockinModels.ReceiveResult> quick(@Valid @RequestBody StockinModels.QuickRequest body,Authentication auth,HttpSession session,HttpServletRequest request){return ApiResponse.ok(service.quick(body,principal(auth),session),id(request));}

    private WmsPrincipal principal(Authentication auth){return (WmsPrincipal)auth.getPrincipal();}
    private String id(HttpServletRequest request){return request.getAttribute(RequestIdFilter.ATTRIBUTE).toString();}
}
