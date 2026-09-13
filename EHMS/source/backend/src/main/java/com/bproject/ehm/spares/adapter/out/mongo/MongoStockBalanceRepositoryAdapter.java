package com.bproject.ehm.spares.adapter.out.mongo;

import com.bproject.ehm.spares.domain.model.SparePart;
import com.bproject.ehm.spares.domain.model.StockBalance;
import com.bproject.ehm.spares.ports.StockBalanceRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class MongoStockBalanceRepositoryAdapter implements StockBalanceRepository {
    private final MongoStockBalanceSpringRepository repository;

    public MongoStockBalanceRepositoryAdapter(MongoStockBalanceSpringRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<StockBalance> find(String warehouseCode, String partCode) {
        String warehouse = warehouseCode == null || warehouseCode.isBlank()
                ? "MAIN" : warehouseCode.trim().toUpperCase(Locale.ROOT);
        return repository.findById(warehouse + ":" + SparePart.normalize(partCode))
                .map(StockBalanceDocument::toDomain);
    }

    @Override
    public List<StockBalance> findAll() {
        return repository.findAll().stream().map(StockBalanceDocument::toDomain).toList();
    }

    @Override
    public StockBalance save(StockBalance balance) {
        return repository.save(StockBalanceDocument.fromDomain(balance)).toDomain();
    }
}
