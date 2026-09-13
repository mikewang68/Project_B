package com.bproject.ehm.spares.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

interface MongoSparePartSpringRepository extends MongoRepository<SparePartDocument, String> {
    List<SparePartDocument> findByActiveTrueOrderByPartCodeAsc();
}
