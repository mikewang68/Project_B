package com.bproject.ehm.spares.adapter.out.mongo;

import com.bproject.ehm.spares.domain.model.SpareReservation;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("spare_reservations")
public class SpareReservationDocument {
    @Id private final String reservationNo;
    private final String workOrderNo;
    private final String assetCode;
    private final String warehouseCode;
    private final String partCode;
    private final String partName;
    private final double requestedQuantity;
    private final double issuedQuantity;
    private final double releasedQuantity;
    private final String unit;
    private final String purpose;
    private final String status;
    private final String requester;
    private final Instant createdAt;
    private final Instant updatedAt;
    @Version private final Long version;

    public SpareReservationDocument(String reservationNo, String workOrderNo, String assetCode,
                                    String warehouseCode, String partCode, String partName,
                                    double requestedQuantity, double issuedQuantity, double releasedQuantity,
                                    String unit, String purpose, String status, String requester,
                                    Instant createdAt, Instant updatedAt, Long version) {
        this.reservationNo = reservationNo;
        this.workOrderNo = workOrderNo;
        this.assetCode = assetCode;
        this.warehouseCode = warehouseCode;
        this.partCode = partCode;
        this.partName = partName;
        this.requestedQuantity = requestedQuantity;
        this.issuedQuantity = issuedQuantity;
        this.releasedQuantity = releasedQuantity;
        this.unit = unit;
        this.purpose = purpose;
        this.status = status;
        this.requester = requester;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    static SpareReservationDocument fromDomain(SpareReservation value) {
        return new SpareReservationDocument(value.reservationNo(), value.workOrderNo(), value.assetCode(),
                value.warehouseCode(), value.partCode(), value.partName(), value.requestedQuantity(),
                value.issuedQuantity(), value.releasedQuantity(), value.unit(), value.purpose(),
                value.status(), value.requester(), value.createdAt(), value.updatedAt(), value.version());
    }

    SpareReservation toDomain() {
        return new SpareReservation(reservationNo, workOrderNo, assetCode, warehouseCode, partCode,
                partName, requestedQuantity, issuedQuantity, releasedQuantity, unit, purpose, status,
                requester, createdAt, updatedAt, version);
    }
}
