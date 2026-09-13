package com.bproject.ehm.spares.adapter.out.mongo;

import com.bproject.ehm.spares.domain.model.SpareReservation;
import com.bproject.ehm.spares.ports.SpareReservationRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MongoSpareReservationRepositoryAdapter implements SpareReservationRepository {
    private final MongoSpareReservationSpringRepository repository;

    public MongoSpareReservationRepositoryAdapter(MongoSpareReservationSpringRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<SpareReservation> findByReservationNo(String reservationNo) {
        return repository.findById(reservationNo).map(SpareReservationDocument::toDomain);
    }

    @Override
    public List<SpareReservation> findRecent(String workOrderNo) {
        List<SpareReservationDocument> values = workOrderNo == null || workOrderNo.isBlank()
                ? repository.findAllByOrderByUpdatedAtDesc()
                : repository.findByWorkOrderNoOrderByUpdatedAtDesc(workOrderNo.trim());
        return values.stream().limit(100).map(SpareReservationDocument::toDomain).toList();
    }

    @Override
    public SpareReservation save(SpareReservation reservation) {
        return repository.save(SpareReservationDocument.fromDomain(reservation)).toDomain();
    }
}
