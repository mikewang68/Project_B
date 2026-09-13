package com.bproject.ehm.spares.domain.model;

import java.time.Instant;

public record StockTransaction(
        String transactionNo,
        String transactionType,
        String warehouseCode,
        String partCode,
        String workOrderNo,
        String reservationNo,
        double quantity,
        double onHandAfter,
        double reservedAfter,
        String reason,
        String operator,
        Instant occurredAt
) {
}
