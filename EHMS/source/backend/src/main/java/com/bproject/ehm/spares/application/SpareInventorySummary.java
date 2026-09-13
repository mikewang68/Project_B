package com.bproject.ehm.spares.application;

import java.math.BigDecimal;

public record SpareInventorySummary(
        int partTypes,
        int lowStockPartTypes,
        int reservedPartTypes,
        BigDecimal onHandValue,
        BigDecimal suggestedPurchaseAmount,
        long activeReservations
) {
}
