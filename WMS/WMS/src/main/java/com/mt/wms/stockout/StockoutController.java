package com.mt.wms.stockout;

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
@RequestMapping("/api/v1/stockout")
class StockoutController {
    private final StockoutService service;
    StockoutController(StockoutService service){this.service=service;}

    @GetMapping @PreAuthorize("hasAuthority('stockout:read')")
    ApiResponse<List<StockoutModels.OrderSummary>> list(Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.list(p(a),s),id(r));}
    @PostMapping @PreAuthorize("hasAuthority('stockout:write')")
    ApiResponse<StockoutModels.OrderDetail> create(@Valid @RequestBody StockoutModels.CreateRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.create(b,p(a),s),id(r));}
    @GetMapping("/{orderId}") @PreAuthorize("hasAuthority('stockout:read')")
    ApiResponse<StockoutModels.OrderDetail> detail(@PathVariable long orderId,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.detail(orderId,p(a),s),id(r));}
    @GetMapping("/{orderId}/allocations") @PreAuthorize("hasAuthority('stockout:read')")
    ApiResponse<List<StockoutModels.AllocationView>> allocations(@PathVariable long orderId,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.allocations(orderId,p(a),s),id(r));}
    @GetMapping("/{orderId}/events") @PreAuthorize("hasAuthority('stockout:read')")
    ApiResponse<List<StockoutModels.EventView>> events(@PathVariable long orderId,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.events(orderId,p(a),s),id(r));}
    @GetMapping("/{orderId}/packages") @PreAuthorize("hasAuthority('stockout:read')")
    ApiResponse<List<StockoutModels.PackageView>> packages(@PathVariable long orderId,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.packages(orderId,p(a),s),id(r));}
    @PostMapping("/{orderId}/allocate") @PreAuthorize("hasAuthority('stockout:write')")
    ApiResponse<StockoutModels.OperationResult> allocate(@PathVariable long orderId,@Valid @RequestBody StockoutModels.AllocateRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.allocate(orderId,b,p(a),s),id(r));}
    @PostMapping("/{orderId}/pick") @PreAuthorize("hasAuthority('stockout:write')")
    ApiResponse<StockoutModels.OperationResult> pick(@PathVariable long orderId,@Valid @RequestBody StockoutModels.PickRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.pick(orderId,b,p(a),s),id(r));}
    @PostMapping("/{orderId}/pick-all") @PreAuthorize("hasAuthority('stockout:write')")
    ApiResponse<StockoutModels.OperationResult> pickAll(@PathVariable long orderId,@Valid @RequestBody StockoutModels.ActionRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.pickAll(orderId,b,p(a),s),id(r));}
    @PostMapping("/{orderId}/packages") @PreAuthorize("hasAuthority('stockout:write')")
    ApiResponse<StockoutModels.PackageView> pack(@PathVariable long orderId,@Valid @RequestBody StockoutModels.PackageRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.createPackage(orderId,b,p(a),s),id(r));}
    @PostMapping("/{orderId}/ship") @PreAuthorize("hasAuthority('stockout:write')")
    ApiResponse<StockoutModels.OperationResult> ship(@PathVariable long orderId,@Valid @RequestBody StockoutModels.ShipRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.ship(orderId,b,p(a),s),id(r));}
    @PostMapping("/{orderId}/cancel-allocation") @PreAuthorize("hasAuthority('stockout:write')")
    ApiResponse<StockoutModels.OrderDetail> cancelAllocation(@PathVariable long orderId,@Valid @RequestBody StockoutModels.ActionRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.cancelAllocation(orderId,b,p(a),s),id(r));}
    @PostMapping("/{orderId}/cancel") @PreAuthorize("hasAuthority('stockout:write')")
    ApiResponse<StockoutModels.OrderDetail> cancel(@PathVariable long orderId,@Valid @RequestBody StockoutModels.ActionRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.cancel(orderId,b,p(a),s),id(r));}
    @PostMapping("/{orderId}/complete") @PreAuthorize("hasAuthority('stockout:write')")
    ApiResponse<StockoutModels.OrderDetail> complete(@PathVariable long orderId,@Valid @RequestBody StockoutModels.ActionRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.complete(orderId,b,p(a),s),id(r));}
    @PostMapping("/{orderId}/reverse") @PreAuthorize("hasAuthority('stockout:write')")
    ApiResponse<String> reverse(@PathVariable long orderId,@Valid @RequestBody StockoutModels.ActionRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.reverse(orderId,b,p(a),s),id(r));}

    @GetMapping("/waves") @PreAuthorize("hasAuthority('stockout:read')")
    ApiResponse<List<StockoutModels.WaveView>> waves(Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.waves(p(a),s),id(r));}
    @GetMapping("/waves/{waveId}") @PreAuthorize("hasAuthority('stockout:read')")
    ApiResponse<StockoutModels.WaveView> wave(@PathVariable long waveId,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.wave(waveId,p(a),s),id(r));}
    @PostMapping("/waves") @PreAuthorize("hasAuthority('stockout:write')")
    ApiResponse<StockoutModels.WaveView> createWave(@Valid @RequestBody StockoutModels.WaveCreateRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.createWave(b,p(a),s),id(r));}
    @PostMapping("/waves/{waveId}/allocate") @PreAuthorize("hasAuthority('stockout:write')")
    ApiResponse<StockoutModels.WaveView> allocateWave(@PathVariable long waveId,@Valid @RequestBody StockoutModels.WaveActionRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.allocateWave(waveId,b,p(a),s),id(r));}
    @PostMapping("/waves/{waveId}/pick") @PreAuthorize("hasAuthority('stockout:write')")
    ApiResponse<StockoutModels.WaveView> pickWave(@PathVariable long waveId,@Valid @RequestBody StockoutModels.ActionRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.pickWave(waveId,b,p(a),s),id(r));}

    private WmsPrincipal p(Authentication a){return (WmsPrincipal)a.getPrincipal();}
    private String id(HttpServletRequest r){return r.getAttribute(RequestIdFilter.ATTRIBUTE).toString();}
}
