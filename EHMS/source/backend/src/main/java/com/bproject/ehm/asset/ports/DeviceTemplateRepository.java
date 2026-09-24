package com.bproject.ehm.asset.ports;
import com.bproject.ehm.asset.domain.model.DeviceTemplate;
import java.util.List;
import java.util.Optional;
public interface DeviceTemplateRepository {
    Optional<DeviceTemplate> findByCode(String code);
    List<DeviceTemplate> findAll();
    DeviceTemplate save(DeviceTemplate value);
    long count();
}
