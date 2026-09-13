package com.bproject.ehm.spares.ports;

import com.bproject.ehm.spares.domain.model.SpareReservation;

import java.util.List;
import java.util.Optional;

public interface SpareReservationRepository {
    Optional<SpareReservation> findByReservationNo(String reservationNo);

    List<SpareReservation> findRecent(String workOrderNo);

    SpareReservation save(SpareReservation reservation);
}
