package com.mt.wms.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public final class InventoryModels {
    private InventoryModels() {}

    public record BalanceView(Long id,String goodCode,String goodName,String barcode,String locationCode,
                              String batchCode,String qualityType,String supplierCode,LocalDate productDate,
                              LocalDate expireDate,String lpn,BigDecimal availableQty,BigDecimal allocatedQty,
                              BigDecimal frozenQty,BigDecimal totalQty,long version) {}
    public record TransactionView(Long id,String transactionType,String operationCode,String goodCode,
                                  String locationCode,BigDecimal quantityBefore,BigDecimal quantityChange,
                                  BigDecimal quantityAfter,String relatedLocationCode,String remark,
                                  String operatorName,OffsetDateTime createdAt) {}
    public record WarningView(String level,String goodCode,String goodName,BigDecimal currentQty,
                              BigDecimal thresholdQty) {}
    public record SerialView(Long id,String serialCode,String goodCode,String goodName,String locationCode,
                             BigDecimal quantity,BigDecimal weightKg,String source,String state,boolean printed,
                             OffsetDateTime createdAt) {}
    public record ReplenishmentRuleView(Long id,String goodCode,String goodName,String locationCode,
                                        BigDecimal minQty,BigDecimal maxQty,BigDecimal currentQty,
                                        BigDecimal suggestedQty,String status,String remark) {}

    public record AdjustmentRequest(@NotBlank String goodCode,@NotBlank String locationCode,
                                    @NotNull @DecimalMin("0") BigDecimal quantityAfter,String batchCode,
                                    String qualityType,String supplierCode,LocalDate productDate,LocalDate expireDate,
                                    String lpn,@NotBlank @Size(max=80) String idempotencyKey,String remark) {}
    public record MoveRequest(@NotNull Long balanceId,@NotBlank String destinationLocationCode,
                              @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal quantity,
                              @NotBlank @Size(max=80) String idempotencyKey,String remark) {}
    public record QuantityRequest(@NotNull Long balanceId,
                                  @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal quantity,
                                  @NotBlank @Size(max=80) String idempotencyKey,String remark) {}
    public record CountRequest(@NotNull Long balanceId,@NotNull @DecimalMin("0") BigDecimal actualQuantity,
                               @NotBlank @Size(max=80) String idempotencyKey,String remark) {}
    public record SerialRequest(@NotNull Long balanceId,String serialCode,
                                @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal quantity,
                                BigDecimal weightKg,String source) {}
    public record ReplenishmentRuleRequest(@NotBlank String goodCode,@NotBlank String locationCode,
                                           @NotNull @DecimalMin("0") BigDecimal minQty,
                                           @NotNull @DecimalMin("0") BigDecimal maxQty,
                                           String status,String remark) {}
    public record ReplenishmentGenerateRequest(@NotBlank @Size(max=80) String idempotencyKey) {}
    public record OperationResult(String operationCode,BalanceView balance) {}
    public record MoveResult(String operationCode,BalanceView source,BalanceView destination) {}
    public record ReplenishmentResult(String operationCode,int ruleCount,int lineCount,BigDecimal movedQuantity) {}
}
