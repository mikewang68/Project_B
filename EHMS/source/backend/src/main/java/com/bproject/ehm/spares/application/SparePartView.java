package com.bproject.ehm.spares.application;

import com.bproject.ehm.spares.domain.model.SparePart;
import com.bproject.ehm.spares.domain.model.StockBalance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SparePartView(
        String partCode,
        String name,
        String specification,
        String category,
        String unit,
        List<String> compatibleAssets,
        double safetyStock,
        double reorderPoint,
        int leadTimeDays,
        BigDecimal unitCost,
        String warehouseCode,
        double onHandQuantity,
        double reservedQuantity,
        double availableQuantity,
        double inTransitQuantity,
        boolean lowStock,
        double suggestedPurchaseQuantity,
        BigDecimal suggestedPurchaseAmount,
        String suggestionReason,
        Instant stockUpdatedAt
) {
    static SparePartView from(SparePart part, StockBalance stock) {
        double available = stock.availableQuantity();
        double effective = available + stock.inTransitQuantity();
        boolean low = effective < part.reorderPoint();
        double target = part.reorderPoint() + part.safetyStock();
        double suggested = low ? roundUp(Math.max(0, target - effective)) : 0;
        BigDecimal amount = part.unitCost().multiply(BigDecimal.valueOf(suggested));
        String reason = low
                ? "可用" + available + part.unit() + "+在途" + stock.inTransitQuantity() + part.unit()
                + "低于补货点" + part.reorderPoint() + part.unit() + "，建议补至补货点+安全库存"
                : "可用库存与在途数量已覆盖补货点";
        return new SparePartView(part.partCode(), part.name(), part.specification(), part.category(),
                part.unit(), part.compatibleAssets(), part.safetyStock(), part.reorderPoint(),
                part.leadTimeDays(), part.unitCost(), stock.warehouseCode(), stock.onHandQuantity(),
                stock.reservedQuantity(), available, stock.inTransitQuantity(), low, suggested,
                amount, reason, stock.updatedAt());
    }

    private static double roundUp(double value) {
        return Math.ceil(value * 1000.0) / 1000.0;
    }
}
