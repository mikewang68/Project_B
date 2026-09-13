package com.bproject.ehm.spares.ports;

import com.bproject.ehm.spares.domain.model.SparePart;

import java.util.List;
import java.util.Optional;

public interface SparePartRepository {
    Optional<SparePart> findByPartCode(String partCode);

    List<SparePart> findAllActive();

    SparePart save(SparePart part);
}
