package com.bproject.ehm.monitoring.ports;

import com.bproject.ehm.monitoring.domain.model.PointQualitySnapshot;
import com.bproject.ehm.monitoring.domain.model.QualityStatus;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PointQualityRepository {
    Optional<PointQualitySnapshot> findByPointCode(String pointCode);

    List<PointQualitySnapshot> findByPointCodes(Collection<String> pointCodes);

    PointQualitySnapshot save(PointQualitySnapshot snapshot);

    Map<QualityStatus, Long> countByStatus(String assetCode, Collection<String> activePointCodes);
}
