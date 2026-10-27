package com.mt.wms.finance;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

final class FinanceModels {
    private FinanceModels() {}

    record MoneyView(Long id,String moneyCode,String relatedOrderCode,String direction,String feeType,String partnerCode,String partnerName,
                     String payType,BigDecimal amount,BigDecimal paidAmount,BigDecimal badDebtAmount,BigDecimal outstandingAmount,
                     String state,LocalDate accountingDate,String remark,OffsetDateTime createdAt,OffsetDateTime finishedAt) {}
    record MoneyDetail(MoneyView money,List<TransactionView> transactions) {}
    record TransactionView(Long id,String transactionCode,BigDecimal amount,String accountLongName,String referenceUser,String operatorName,String remark,OffsetDateTime createdAt) {}
    record AccountView(Long id,String organ,String name,String accountNo,String longName,String status,String remark,OffsetDateTime createdAt) {}
    record SummaryView(String month,BigDecimal incomeAmount,BigDecimal incomePaid,BigDecimal incomeOutstanding,
                       BigDecimal outcomeAmount,BigDecimal outcomePaid,BigDecimal outcomeOutstanding,BigDecimal grossProfit,List<FeeSummary> fees) {}
    record FeeSummary(String direction,String feeType,BigDecimal amount,BigDecimal paidAmount,BigDecimal badDebtAmount) {}
    record TrendPoint(String month,BigDecimal incomeAmount,BigDecimal incomePaid,BigDecimal outcomeAmount,BigDecimal outcomePaid) {}
    record PartnerSummary(String partnerCode,String partnerName,BigDecimal amount,BigDecimal paidAmount,BigDecimal outstandingAmount) {}
    record GoodsReport(String goodCode,String goodName,String barcode,String specification,String supplierCode,String qualityType,
                       BigDecimal plannedQty,BigDecimal actualQty,BigDecimal amount) {}

    record CreateMoneyRequest(@NotBlank String direction,@NotBlank String feeType,String relatedOrderCode,String partnerCode,String payType,
                              @NotNull @DecimalMin("0.01") BigDecimal amount,@NotNull LocalDate accountingDate,String remark,
                              @NotBlank @Size(max=80) String idempotencyKey) {}
    record UpdateMoneyRequest(@NotBlank String feeType,String relatedOrderCode,String partnerCode,String payType,
                              @NotNull @DecimalMin("0.01") BigDecimal amount,@NotNull LocalDate accountingDate,String remark) {}
    record PaymentRequest(@NotNull @DecimalMin("0.01") BigDecimal amount,Long accountId,String referenceUser,boolean partComplete,
                          String remark,@NotBlank @Size(max=80) String idempotencyKey) {}
    record ActionRequest(String remark) {}
    record SaveAccountRequest(@NotBlank String organ,@NotBlank String name,@NotBlank String accountNo,String status,String remark) {}
}
