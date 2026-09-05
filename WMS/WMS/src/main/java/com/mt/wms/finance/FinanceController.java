package com.mt.wms.finance;

import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/finance")
class FinanceController {
    private final FinanceService service;
    FinanceController(FinanceService service){this.service=service;}
    @GetMapping("/money") @PreAuthorize("hasAuthority('finance:read')") ApiResponse<List<FinanceModels.MoneyView>> list(@RequestParam(defaultValue="")String direction,@RequestParam(defaultValue="")String state,@RequestParam(defaultValue="")String q,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.list(direction,state,q,p(a),s),id(r));}
    @PostMapping("/money") @PreAuthorize("hasAuthority('finance:write')") ApiResponse<FinanceModels.MoneyDetail> create(@Valid @RequestBody FinanceModels.CreateMoneyRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.create(b,p(a),s),id(r));}
    @GetMapping("/money/{moneyId}") @PreAuthorize("hasAuthority('finance:read')") ApiResponse<FinanceModels.MoneyDetail> detail(@PathVariable long moneyId,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.detail(moneyId,p(a),s),id(r));}
    @PutMapping("/money/{moneyId}") @PreAuthorize("hasAuthority('finance:write')") ApiResponse<FinanceModels.MoneyDetail> update(@PathVariable long moneyId,@Valid @RequestBody FinanceModels.UpdateMoneyRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.update(moneyId,b,p(a),s),id(r));}
    @PostMapping("/money/{moneyId}/transactions") @PreAuthorize("hasAuthority('finance:write')") ApiResponse<FinanceModels.MoneyDetail> pay(@PathVariable long moneyId,@Valid @RequestBody FinanceModels.PaymentRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.pay(moneyId,b,p(a),s),id(r));}
    @PostMapping("/money/{moneyId}/cancel") @PreAuthorize("hasAuthority('finance:write')") ApiResponse<FinanceModels.MoneyDetail> cancel(@PathVariable long moneyId,@RequestBody FinanceModels.ActionRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.cancel(moneyId,b,p(a),s),id(r));}
    @GetMapping("/accounts") @PreAuthorize("hasAuthority('finance:read')") ApiResponse<List<FinanceModels.AccountView>> accounts(Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.accounts(p(a),s),id(r));}
    @PostMapping("/accounts") @PreAuthorize("hasAuthority('finance:write')") ApiResponse<FinanceModels.AccountView> account(@Valid @RequestBody FinanceModels.SaveAccountRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.saveAccount(null,b,p(a),s),id(r));}
    @PutMapping("/accounts/{accountId}") @PreAuthorize("hasAuthority('finance:write')") ApiResponse<FinanceModels.AccountView> account(@PathVariable long accountId,@Valid @RequestBody FinanceModels.SaveAccountRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.saveAccount(accountId,b,p(a),s),id(r));}
    @GetMapping("/summary") @PreAuthorize("hasAuthority('finance:read')") ApiResponse<FinanceModels.SummaryView> summary(@RequestParam(defaultValue="")String month,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.summary(month,p(a),s),id(r));}
    @GetMapping("/trend") @PreAuthorize("hasAuthority('finance:read')") ApiResponse<List<FinanceModels.TrendPoint>> trend(Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.trend(p(a),s),id(r));}
    @GetMapping("/partner-summary") @PreAuthorize("hasAuthority('finance:read')") ApiResponse<List<FinanceModels.PartnerSummary>> partners(@RequestParam(defaultValue="INCOME")String direction,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.partnerSummary(direction,p(a),s),id(r));}
    @GetMapping("/reports/stockin") @PreAuthorize("hasAuthority('finance:read')") ApiResponse<List<FinanceModels.GoodsReport>> stockin(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate from,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate to,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.stockin(from,to,p(a),s),id(r));}
    @GetMapping("/reports/stockout") @PreAuthorize("hasAuthority('finance:read')") ApiResponse<List<FinanceModels.GoodsReport>> stockout(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate from,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate to,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.stockout(from,to,p(a),s),id(r));}
    private static WmsPrincipal p(Authentication a){return (WmsPrincipal)a.getPrincipal();}
    private static String id(HttpServletRequest r){return (String)r.getAttribute(RequestIdFilter.ATTRIBUTE);}
}
