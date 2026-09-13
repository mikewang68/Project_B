package com.bproject.ehm.spares.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;

public record StockBalance(
        String balanceId,
        String warehouseCode,
        String partCode,
        double onHandQuantity,
        double reservedQuantity,
        double inTransitQuantity,
        Instant updatedAt,
        Long version
) {
    public static StockBalance empty(String warehouseCode, String partCode, Instant now) {
        String warehouse = normalizeWarehouse(warehouseCode);
        String part = SparePart.normalize(partCode);
        return new StockBalance(warehouse + ":" + part, warehouse, part, 0, 0, 0, now, null);
    }

    public double availableQuantity() {
        return round(onHandQuantity - reservedQuantity);
    }

    public StockBalance receive(double quantity, Instant now) {
        requirePositive(quantity);
        return copy(onHandQuantity + quantity, reservedQuantity, inTransitQuantity, now);
    }

    public StockBalance reserve(double quantity, Instant now) {
        requirePositive(quantity);
        if (quantity > availableQuantity() + 0.000001) {
            throw new DomainConflictException("可用库存不足：可用" + availableQuantity() + "，申请预留" + quantity);
        }
        return copy(onHandQuantity, reservedQuantity + quantity, inTransitQuantity, now);
    }

    public StockBalance issue(double quantity, Instant now) {
        requirePositive(quantity);
        if (quantity > reservedQuantity + 0.000001) {
            throw new DomainConflictException("领用数量超过已预留数量");
        }
        if (quantity > onHandQuantity + 0.000001) {
            throw new DomainConflictException("账面库存不足，不能领用");
        }
        return copy(onHandQuantity - quantity, reservedQuantity - quantity, inTransitQuantity, now);
    }

    public StockBalance release(double quantity, Instant now) {
        requirePositive(quantity);
        if (quantity > reservedQuantity + 0.000001) {
            throw new DomainConflictException("释放数量超过已预留数量");
        }
        return copy(onHandQuantity, reservedQuantity - quantity, inTransitQuantity, now);
    }

    public StockBalance returnToStock(double quantity, Instant now) {
        return receive(quantity, now);
    }

    private StockBalance copy(double onHand, double reserved, double inTransit, Instant now) {
        return new StockBalance(balanceId, warehouseCode, partCode, round(onHand),
                round(reserved), round(inTransit), now, version);
    }

    private static void requirePositive(double quantity) {
        if (!Double.isFinite(quantity) || quantity <= 0) throw new IllegalArgumentException("数量必须大于0");
    }

    private static String normalizeWarehouse(String value) {
        return value == null || value.isBlank() ? "MAIN" : value.trim().toUpperCase();
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
