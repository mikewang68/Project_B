package com.bproject.ehm.spares.application;

import com.bproject.ehm.spares.domain.model.SpareReservation;
import com.bproject.ehm.spares.domain.model.StockBalance;
import com.bproject.ehm.spares.domain.model.StockTransaction;

public record SpareOperationResult(
        StockBalance balance,
        SpareReservation reservation,
        StockTransaction transaction
) {
}
