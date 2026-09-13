package com.bproject.ehm.spares.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

interface MongoStockBalanceSpringRepository extends MongoRepository<StockBalanceDocument, String> {
}
