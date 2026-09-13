package com.bproject.ehm.spares.adapter.out.mongo;

import com.bproject.ehm.spares.domain.model.StockTransaction;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("spare_stock_transactions")
public class StockTransactionDocument {
    @Id private final String transactionNo;
    private final String transactionType;
    private final String warehouseCode;
    private final String partCode;
    private final String workOrderNo;
    private final String reservationNo;
    private final double quantity;
    private final double onHandAfter;
    private final double reservedAfter;
    private final String reason;
    private final String operator;
    private final Instant occurredAt;

    public StockTransactionDocument(String transactionNo, String transactionType, String warehouseCode,
                                    String partCode, String workOrderNo, String reservationNo,
                                    double quantity, double onHandAfter, double reservedAfter,
                                    String reason, String operator, Instant occurredAt) {
        this.transactionNo = transactionNo;
        this.transactionType = transactionType;
        this.warehouseCode = warehouseCode;
        this.partCode = partCode;
        this.workOrderNo = workOrderNo;
        this.reservationNo = reservationNo;
        this.quantity = quantity;
        this.onHandAfter = onHandAfter;
        this.reservedAfter = reservedAfter;
        this.reason = reason;
        this.operator = operator;
        this.occurredAt = occurredAt;
    }

    static StockTransactionDocument fromDomain(StockTransaction value) {
        return new StockTransactionDocument(value.transactionNo(), value.transactionType(),
                value.warehouseCode(), value.partCode(), value.workOrderNo(), value.reservationNo(),
                value.quantity(), value.onHandAfter(), value.reservedAfter(), value.reason(),
                value.operator(), value.occurredAt());
    }

    StockTransaction toDomain() {
        return new StockTransaction(transactionNo, transactionType, warehouseCode, partCode,
                workOrderNo, reservationNo, quantity, onHandAfter, reservedAfter, reason,
                operator, occurredAt);
    }
}
