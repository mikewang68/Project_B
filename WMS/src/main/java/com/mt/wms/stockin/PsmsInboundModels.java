package com.mt.wms.stockin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Version-one contract. Quantities are integral stocking units, never an implicit unit conversion. */
public final class PsmsInboundModels {
    private PsmsInboundModels() {}
    public record TaskRequest(
            @NotNull @Pattern(regexp="1\\.0") String schemaVersion,
            @NotBlank @Size(max=96) String eventId,
            @NotNull @Pattern(regexp="[A-Za-z0-9_.:-]{1,64}") String taskId,
            @NotNull @Min(1) @Max(1) Integer version,
            @NotBlank @Size(max=64) String planId,
            @NotBlank @Size(max=64) String workOrderId,
            @NotBlank @Size(max=64) String companyCode,
            @NotBlank @Size(max=64) String ownerCode,
            @NotNull OffsetDateTime expectedArrivalAt,
            @NotEmpty @Size(max=100) List<@Valid TaskLineRequest> lines) {}
    public record TaskLineRequest(
            @NotBlank @Size(max=64) String lineId,
            @NotBlank @Size(max=64) String goodCode,
            @NotNull @DecimalMin("1") @DecimalMax("999999999") BigDecimal plannedQty,
            @NotBlank @Size(max=32) String unit,
            @Size(max=64) String batchCode,
            @Size(max=64) String supplierCode) {}
    public record Accepted(String taskId,int version,String status) {}
    public record ConfirmRequest(@NotBlank @Size(max=64) String warehouseCode,
                                 @NotBlank @Size(max=64) String ownerCode) {}
    public record LineView(String lineId,String goodCode,String unit,BigDecimal plannedQty,
                           BigDecimal receivedQty,BigDecimal remainingQty,String batchCode,String supplierCode) {}
    public record TaskView(String taskId,int version,String planId,String workOrderId,
                           String companyCode,String ownerCode,OffsetDateTime expectedArrivalAt,String status,
                           String warehouseCode,String wmsOrderCode,Long wmsOrderId,int lastSequence,
                           List<LineView> lines,String deliveryState,String deliveryError) {}
    public record ReceiptLine(String lineId,String goodCode,String unit,BigDecimal receivedDelta,
                              BigDecimal receivedQty,BigDecimal remainingQty) {}
    public record InboundEvent(String schemaVersion,String eventId,String taskId,int version,int sequence,
                               String eventType,OffsetDateTime occurredAt,String companyCode,String ownerCode,
                               String warehouseCode,String wmsOrderCode,String receiptOperationCode,
                               List<ReceiptLine> lines) {}
}
