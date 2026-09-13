package com.bproject.ehm.spares.adapter.out.mongo;

import com.bproject.ehm.spares.domain.model.StockBalance;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("spare_stock_balances")
public class StockBalanceDocument {
    @Id private final String balanceId;
    private final String warehouseCode;
    private final String partCode;
    private final double onHandQuantity;
    private final double reservedQuantity;
    private final double inTransitQuantity;
    private final Instant updatedAt;
    @Version private final Long version;

    public StockBalanceDocument(String balanceId, String warehouseCode, String partCode,
                                double onHandQuantity, double reservedQuantity, double inTransitQuantity,
                                Instant updatedAt, Long version) {
        this.balanceId = balanceId;
        this.warehouseCode = warehouseCode;
        this.partCode = partCode;
        this.onHandQuantity = onHandQuantity;
        this.reservedQuantity = reservedQuantity;
        this.inTransitQuantity = inTransitQuantity;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    static StockBalanceDocument fromDomain(StockBalance balance) {
        return new StockBalanceDocument(balance.balanceId(), balance.warehouseCode(), balance.partCode(),
                balance.onHandQuantity(), balance.reservedQuantity(), balance.inTransitQuantity(),
                balance.updatedAt(), balance.version());
    }

    StockBalance toDomain() {
        return new StockBalance(balanceId, warehouseCode, partCode, onHandQuantity,
                reservedQuantity, inTransitQuantity, updatedAt, version);
    }
}
