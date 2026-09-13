package com.bproject.ehm.spares.ports;

import com.bproject.ehm.spares.domain.model.StockTransaction;

import java.util.List;

public interface StockTransactionRepository {
    List<StockTransaction> findRecent(String partCode, int limit);

    StockTransaction save(StockTransaction transaction);
}
