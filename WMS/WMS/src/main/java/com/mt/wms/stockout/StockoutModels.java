package com.mt.wms.stockout;

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

public final class StockoutModels {
    private StockoutModels() {}

    public record OrderSummary(Long id,String orderCode,String externalOrderCode,String outboundType,String source,
                               String state,String allocationState,String pickState,String shipState,
                               String partnerCode,String partnerName,LocalDate plannedDate,
                               BigDecimal plannedQty,BigDecimal allocatedQty,BigDecimal pickedQty,BigDecimal shippedQty,
                               BigDecimal totalAmount,OffsetDateTime createdAt,OffsetDateTime completedAt) {}
    public record OrderDetail(Long id,String orderCode,String externalOrderCode,String relatedOrderCode,
                              String outboundType,String source,String state,String allocationState,String pickState,String shipState,
                              String partnerCode,String partnerName,LocalDate plannedDate,String receiverName,
                              String receiverPhone,String receiverAddress,String carrierCode,String trackingCode,
                              BigDecimal plannedQty,BigDecimal allocatedQty,BigDecimal pickedQty,BigDecimal shippedQty,
                              BigDecimal totalAmount,String targetWarehouseCode,String targetOwnerCode,String transferInOrderCode,
                              String remark,OffsetDateTime createdAt,OffsetDateTime completedAt,List<LineView> lines) {}
    public record LineView(Long id,int lineNo,String goodCode,String goodName,String barcode,
                           BigDecimal plannedQty,BigDecimal allocatedQty,BigDecimal pickedQty,BigDecimal shippedQty,
                           BigDecimal unallocatedQty,BigDecimal unpickedQty,BigDecimal unshippedQty,
                           String supplierCode,String qualityType,String batchCode,BigDecimal unitPrice,String remark) {}
    public record AllocationView(Long id,int lineNo,String goodCode,String goodName,String locationCode,
                                 String batchCode,String qualityType,String supplierCode,String lpn,
                                 BigDecimal allocatedQty,BigDecimal pickedQty,BigDecimal shippedQty,String state,OffsetDateTime createdAt) {}
    public record EventView(Long id,String eventType,String operationCode,int lineNo,String goodCode,String goodName,
                            String locationCode,BigDecimal quantity,String operatorName,String remark,OffsetDateTime createdAt) {}
    public record PackageLineView(Long id,Long orderLineId,int lineNo,String goodCode,String goodName,BigDecimal quantity) {}
    public record PackageView(Long id,String packageCode,String carrierCode,String trackingCode,String state,
                              OffsetDateTime shippedAt,String remark,OffsetDateTime createdAt,List<PackageLineView> lines) {}
    public record WaveView(Long id,String waveCode,String state,int orderCount,String remark,OffsetDateTime createdAt,List<String> orderCodes) {}

    public record LineRequest(@NotBlank String goodCode,
                              @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal plannedQty,
                              String supplierCode,String qualityType,String batchCode,
                              @DecimalMin("0") BigDecimal unitPrice,String remark) {}
    public record CreateRequest(@NotBlank String outboundType,String source,String externalOrderCode,String relatedOrderCode,
                                String partnerCode,LocalDate plannedDate,String receiverName,String receiverPhone,
                                String receiverAddress,String carrierCode,String trackingCode,
                                String targetWarehouseCode,String targetOwnerCode,String remark,
                                @NotBlank @Size(max=60) String idempotencyKey,
                                @NotEmpty List<@Valid LineRequest> lines) {}
    public record AllocateRequest(boolean allowPartial,@NotBlank @Size(max=60) String idempotencyKey,String remark) {}
    public record PickLineRequest(Long lineId,String barcode,
                                  @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal quantity,
                                  List<@NotBlank @Size(max=128) String> serialCodes,String remark) {}
    public record PickRequest(@NotBlank @Size(max=60) String idempotencyKey,@NotEmpty List<@Valid PickLineRequest> lines) {}
    public record ActionRequest(@NotBlank @Size(max=60) String idempotencyKey,String remark) {}
    public record PackageLineRequest(@NotNull Long lineId,@NotNull @DecimalMin(value="0",inclusive=false) BigDecimal quantity) {}
    public record PackageRequest(String carrierCode,String trackingCode,String remark,
                                 @NotBlank @Size(max=60) String idempotencyKey,
                                 @NotEmpty List<@Valid PackageLineRequest> lines) {}
    public record ShipRequest(Long packageId,@NotBlank @Size(max=60) String idempotencyKey,String remark) {}
    public record WaveCreateRequest(@NotEmpty @Size(min=2) List<@NotNull Long> orderIds,String remark,
                                    @NotBlank @Size(max=60) String idempotencyKey) {}
    public record WaveActionRequest(boolean allowPartial,@NotBlank @Size(max=60) String idempotencyKey,String remark) {}
    public record OperationResult(String operationCode,OrderDetail order,BigDecimal quantity) {}
}
