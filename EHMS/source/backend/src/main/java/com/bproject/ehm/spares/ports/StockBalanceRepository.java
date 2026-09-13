package com.bproject.ehm.spares.ports;

import com.bproject.ehm.spares.domain.model.StockBalance;

import java.util.List;
import java.util.Optional;

public interface StockBalanceRepository {
    Optional<StockBalance> find(String warehouseCode, String partCode);

    List<StockBalance> findAll();

    StockBalance save(StockBalance balance);
}
