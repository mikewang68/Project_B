package com.mt.wms.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

final class InventoryCountModels {
    private InventoryCountModels() {}

    record Submit(@NotNull @Positive Long balanceId, @NotNull @PositiveOrZero Long balanceVersion,
                  @NotNull @DecimalMin("0") @Digits(integer=15, fraction=3) BigDecimal actualQuantity,
                  @NotNull @DecimalMin("0") @Digits(integer=15, fraction=3) BigDecimal lossQuantity,
                  @NotNull @DecimalMin("0") @Digits(integer=15, fraction=3) BigDecimal missingQuantity,
                  @Size(max=256) String reason, @NotBlank @Size(max=80) String idempotencyKey) {}

    enum Decision { POST, REJECT }
    enum State { PENDING, POSTED, REJECTED }
    record Review(@NotNull Decision decision, @Size(max=256) String note) {}

    record View(long id, long balanceId, long balanceVersion, String goodCode, String goodName,
                String locationCode, String batchCode, String qualityType, String supplierCode, String lpn,
                String unit, BigDecimal bookQty, BigDecimal actualQty, BigDecimal differenceQty,
                BigDecimal lossQty, BigDecimal missingQty, String reason, State state,
                String createdBy, OffsetDateTime createdAt, String reviewedBy, OffsetDateTime reviewedAt,
                String reviewNote, String operationCode) {}
}
