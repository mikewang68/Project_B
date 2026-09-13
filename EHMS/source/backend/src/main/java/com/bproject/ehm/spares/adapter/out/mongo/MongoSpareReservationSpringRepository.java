package com.bproject.ehm.spares.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

interface MongoSpareReservationSpringRepository extends MongoRepository<SpareReservationDocument, String> {
    List<SpareReservationDocument> findByWorkOrderNoOrderByUpdatedAtDesc(String workOrderNo);
    List<SpareReservationDocument> findAllByOrderByUpdatedAtDesc();
}
