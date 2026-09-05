package com.mt.wms.masterdata;

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
@RequestMapping("/api/v1/master-data")
class MasterDataController {
    private final MasterDataService service;
    MasterDataController(MasterDataService service){this.service=service;}
    @GetMapping("/{resource}") @PreAuthorize("hasAuthority('master:read')")
    ApiResponse<List<MasterDataModels.Item>> list(@PathVariable String resource,Authentication auth,HttpSession session,HttpServletRequest req){return ApiResponse.ok(service.list(MasterResource.fromPath(resource),(WmsPrincipal)auth.getPrincipal(),session),id(req));}
    @PostMapping("/{resource}") @PreAuthorize("hasAuthority('master:write')")
    ApiResponse<MasterDataModels.Item> create(@PathVariable String resource,@Valid @RequestBody MasterDataModels.SaveRequest body,Authentication auth,HttpSession session,HttpServletRequest req){return ApiResponse.ok(service.save(MasterResource.fromPath(resource),null,body,(WmsPrincipal)auth.getPrincipal(),session),id(req));}
    @PutMapping("/{resource}/{itemId}") @PreAuthorize("hasAuthority('master:write')")
    ApiResponse<MasterDataModels.Item> update(@PathVariable String resource,@PathVariable long itemId,@Valid @RequestBody MasterDataModels.SaveRequest body,Authentication auth,HttpSession session,HttpServletRequest req){return ApiResponse.ok(service.save(MasterResource.fromPath(resource),itemId,body,(WmsPrincipal)auth.getPrincipal(),session),id(req));}
    private String id(HttpServletRequest r){return r.getAttribute(RequestIdFilter.ATTRIBUTE).toString();}
}
