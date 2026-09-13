package com.bproject.ehm.spares.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;

public record SpareReservation(
        String reservationNo,
        String workOrderNo,
        String assetCode,
        String warehouseCode,
        String partCode,
        String partName,
        double requestedQuantity,
        double issuedQuantity,
        double releasedQuantity,
        String unit,
        String purpose,
        String status,
        String requester,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static SpareReservation create(String reservationNo, String workOrderNo, String assetCode,
                                          String warehouseCode, SparePart part, double quantity,
                                          String purpose, String requester, Instant now) {
        requirePositive(quantity);
        return new SpareReservation(required(reservationNo, "预留编号"),
                required(workOrderNo, "工单编号"), required(assetCode, "设备编码"),
                fallback(warehouseCode, "MAIN").toUpperCase(), part.partCode(), part.name(),
                quantity, 0, 0, part.unit(), fallback(purpose, "工单维修使用"),
                "RESERVED", fallback(requester, "Demo库管员"), now, now, null);
    }

    public double remainingQuantity() {
        return round(requestedQuantity - issuedQuantity - releasedQuantity);
    }

    public SpareReservation issue(double quantity, Instant now) {
        requireActive();
        requirePositive(quantity);
        if (quantity > remainingQuantity() + 0.000001) {
            throw new DomainConflictException("领用数量超过预留剩余数量");
        }
        double issued = round(issuedQuantity + quantity);
        String target = Math.abs(requestedQuantity - issued - releasedQuantity) < 0.000001
                ? "ISSUED" : "PARTIALLY_ISSUED";
        return copy(issued, releasedQuantity, target, now);
    }

    public SpareReservation release(String reason, Instant now) {
        requireActive();
        double remaining = remainingQuantity();
        if (remaining <= 0) throw new DomainConflictException("该预留没有可释放数量");
        return new SpareReservation(reservationNo, workOrderNo, assetCode, warehouseCode,
                partCode, partName, requestedQuantity, issuedQuantity,
                round(releasedQuantity + remaining), unit,
                purpose + "；释放原因：" + fallback(reason, "不再需要"), "RELEASED",
                requester, createdAt, now, version);
    }

    private void requireActive() {
        if ("ISSUED".equals(status) || "RELEASED".equals(status)) {
            throw new DomainConflictException("预留已结束，不能继续操作");
        }
    }

    private SpareReservation copy(double issued, double released, String target, Instant now) {
        return new SpareReservation(reservationNo, workOrderNo, assetCode, warehouseCode,
                partCode, partName, requestedQuantity, issued, released, unit, purpose,
                target, requester, createdAt, now, version);
    }

    private static void requirePositive(double value) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("数量必须大于0");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
