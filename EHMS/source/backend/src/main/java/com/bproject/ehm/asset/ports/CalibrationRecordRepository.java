package com.bproject.ehm.asset.ports;
import com.bproject.ehm.asset.domain.model.CalibrationRecord;
import java.util.List;
import java.util.Optional;
public interface CalibrationRecordRepository {
    Optional<CalibrationRecord> findByNo(String no);
    List<CalibrationRecord> findAll();
    CalibrationRecord save(CalibrationRecord value);
    long count();
}
