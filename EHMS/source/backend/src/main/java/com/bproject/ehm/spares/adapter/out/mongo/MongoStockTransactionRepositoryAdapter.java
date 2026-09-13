package com.bproject.ehm.spares.adapter.out.mongo;

import com.bproject.ehm.spares.domain.model.SparePart;
import com.bproject.ehm.spares.domain.model.StockTransaction;
import com.bproject.ehm.spares.ports.StockTransactionRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MongoStockTransactionRepositoryAdapter implements StockTransactionRepository {
    private final MongoStockTransactionSpringRepository repository;

    public MongoStockTransactionRepositoryAdapter(MongoStockTransactionSpringRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<StockTransaction> findRecent(String partCode, int limit) {
        List<StockTransactionDocument> values = partCode == null || partCode.isBlank()
                ? repository.findTop100ByOrderByOccurredAtDesc()
                : repository.findTop100ByPartCodeOrderByOccurredAtDesc(SparePart.normalize(partCode));
        int size = Math.max(1, Math.min(limit, 100));
        return values.stream().limit(size).map(StockTransactionDocument::toDomain).toList();
    }

    @Override
    public StockTransaction save(StockTransaction transaction) {
        return repository.save(StockTransactionDocument.fromDomain(transaction)).toDomain();
    }
}
