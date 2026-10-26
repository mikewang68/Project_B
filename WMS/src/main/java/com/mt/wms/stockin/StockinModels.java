package com.mt.wms.stockin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class StockinModels {
    private StockinModels() {}

    public record OrderSummary(Long id,String orderCode,String externalOrderCode,String inboundType,String source,
                               String state,String partnerCode,String partnerName,LocalDate plannedDate,
                               BigDecimal plannedQty,BigDecimal receivedQty,BigDecimal totalAmount,
                               OffsetDateTime createdAt,OffsetDateTime finishedAt) {}
    public record OrderDetail(Long id,String orderCode,String externalOrderCode,String relatedOrderCode,
                              String inboundType,String source,String state,String partnerCode,String partnerName,
                              LocalDate plannedDate,BigDecimal plannedQty,BigDecimal receivedQty,
                              BigDecimal totalAmount,String remark,OffsetDateTime createdAt,
                              OffsetDateTime finishedAt,List<LineView> lines) {}
    public record LineView(Long id,int lineNo,String goodCode,String goodName,String barcode,
                           BigDecimal plannedQty,BigDecimal receivedQty,BigDecimal remainingQty,
                           String preferredLocationCode,String supplierCode,String qualityType,
                           LocalDate productDate,LocalDate expireDate,String batchCode,
                           BigDecimal unitPrice,String remark) {}
    public record ReceiptView(Long id,String operationCode,int lineNo,String goodCode,String goodName,
                              String locationCode,BigDecimal quantity,String batchCode,String qualityType,
                              String lpn,int serialCount,String operatorName,String remark,
                              OffsetDateTime createdAt) {}
    public record LocationSuggestion(String locationCode,String locationName,String reason) {}

    public record LineRequest(@NotBlank String goodCode,
                              @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal plannedQty,
                              String preferredLocationCode,String supplierCode,String qualityType,
                              LocalDate productDate,LocalDate expireDate,String batchCode,
                              @DecimalMin("0") BigDecimal unitPrice,String remark) {}
    public record CreateRequest(@NotBlank String inboundType,String source,String externalOrderCode,
                                String relatedOrderCode,String partnerCode,LocalDate plannedDate,String remark,
                                @NotBlank @Size(max=60) String idempotencyKey,
                                @NotEmpty List<@Valid LineRequest> lines) {}
    public record ReceiveLineRequest(@NotNull Long lineId,
                                     @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal quantity,
                                     @NotBlank String locationCode,String lpn,
                                     List<@NotBlank @Size(max=128) String> serialCodes,String remark) {}
    public record ReceiveRequest(@NotBlank @Size(max=60) String idempotencyKey,
                                 boolean allowOverReceipt,
                                 @NotEmpty List<@Valid ReceiveLineRequest> lines) {}
    public record ScanRequest(@NotBlank String barcode,
                              @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal quantity,
                              @NotBlank String locationCode,String lpn,
                              List<@NotBlank @Size(max=128) String> serialCodes,String remark,
                              boolean allowOverReceipt,
                              @NotBlank @Size(max=60) String idempotencyKey) {}
    public record ActionRequest(@NotBlank @Size(max=60) String idempotencyKey,String remark) {}
    public record QuickRequest(@NotBlank String inboundType,@NotBlank String goodCode,
                               @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal quantity,
                               @NotBlank String locationCode,String partnerCode,String supplierCode,
                               String qualityType,LocalDate productDate,LocalDate expireDate,String batchCode,
                               String lpn,@DecimalMin("0") BigDecimal unitPrice,
                               List<@NotBlank @Size(max=128) String> serialCodes,String remark,
                               @NotBlank @Size(max=60) String idempotencyKey) {}
    public record ReceiveResult(String operationCode,OrderDetail order,int receiptCount,BigDecimal receivedQuantity) {}
}
