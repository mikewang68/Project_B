package com.bproject.ehm.spares.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

interface MongoStockTransactionSpringRepository extends MongoRepository<StockTransactionDocument, String> {
    List<StockTransactionDocument> findTop100ByOrderByOccurredAtDesc();
    List<StockTransactionDocument> findTop100ByPartCodeOrderByOccurredAtDesc(String partCode);
}
