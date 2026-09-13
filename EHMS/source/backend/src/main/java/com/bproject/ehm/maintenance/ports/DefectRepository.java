package com.bproject.ehm.maintenance.ports;

import com.bproject.ehm.maintenance.domain.model.DefectRecord;

import java.util.List;
import java.util.Optional;

public interface DefectRepository {
    Optional<DefectRecord> findByDefectNo(String defectNo);

    List<DefectRecord> findByAssetCode(String assetCode);

    DefectRecord save(DefectRecord defect);
}
