package com.bproject.ehm.spares.adapter.out.mongo;

import com.bproject.ehm.spares.domain.model.SparePart;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Document("spare_parts")
public class SparePartDocument {
    @Id private final String partCode;
    private final String name;
    private final String specification;
    private final String category;
    private final String unit;
    private final List<String> compatibleAssets;
    private final double safetyStock;
    private final double reorderPoint;
    private final int leadTimeDays;
    private final BigDecimal unitCost;
    private final boolean active;
    private final Instant createdAt;
    private final Instant updatedAt;
    @Version private final Long version;

    public SparePartDocument(String partCode, String name, String specification, String category,
                             String unit, List<String> compatibleAssets, double safetyStock,
                             double reorderPoint, int leadTimeDays, BigDecimal unitCost,
                             boolean active, Instant createdAt, Instant updatedAt, Long version) {
        this.partCode = partCode;
        this.name = name;
        this.specification = specification;
        this.category = category;
        this.unit = unit;
        this.compatibleAssets = compatibleAssets;
        this.safetyStock = safetyStock;
        this.reorderPoint = reorderPoint;
        this.leadTimeDays = leadTimeDays;
        this.unitCost = unitCost;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    static SparePartDocument fromDomain(SparePart part) {
        return new SparePartDocument(part.partCode(), part.name(), part.specification(), part.category(),
                part.unit(), part.compatibleAssets(), part.safetyStock(), part.reorderPoint(),
                part.leadTimeDays(), part.unitCost(), part.active(), part.createdAt(),
                part.updatedAt(), part.version());
    }

    SparePart toDomain() {
        return new SparePart(partCode, name, specification, category, unit, compatibleAssets,
                safetyStock, reorderPoint, leadTimeDays, unitCost, active, createdAt, updatedAt, version);
    }
}
