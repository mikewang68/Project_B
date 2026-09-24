package com.bproject.ehm.workbench.ports;

import com.bproject.ehm.workbench.domain.model.ShiftHandover;
import java.util.List;
import java.util.Optional;

public interface ShiftHandoverRepository {
    Optional<ShiftHandover> findByHandoverNo(String handoverNo);
    List<ShiftHandover> findAll();
    ShiftHandover save(ShiftHandover handover);
    long count();
}
