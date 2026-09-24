package com.bproject.ehm.platform.persistence.opengauss;

import com.bproject.ehm.asset.domain.model.CalibrationRecord;
import com.bproject.ehm.asset.domain.model.ConfigurationChange;
import com.bproject.ehm.asset.domain.model.DeviceTemplate;
import com.bproject.ehm.asset.ports.CalibrationRecordRepository;
import com.bproject.ehm.asset.ports.ConfigurationChangeRepository;
import com.bproject.ehm.asset.ports.DeviceTemplateRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("server")
class OpenGaussDeviceTemplateRepository extends OpenGaussAdapterSupport implements DeviceTemplateRepository {
    private static final String TYPE = "device-template";
    OpenGaussDeviceTemplateRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<DeviceTemplate> findByCode(String code) { return store.find(TYPE, code, DeviceTemplate.class); }
    public List<DeviceTemplate> findAll() { return store.findAll(TYPE, DeviceTemplate.class).stream()
            .sorted(Comparator.comparing(DeviceTemplate::updatedAt, newestFirst())).toList(); }
    public DeviceTemplate save(DeviceTemplate value) { return store.save(TYPE, value.templateCode(), value); }
    public long count() { return store.count(TYPE); }
}

@Repository
@Profile("server")
class OpenGaussCalibrationRecordRepository extends OpenGaussAdapterSupport implements CalibrationRecordRepository {
    private static final String TYPE = "calibration-record";
    OpenGaussCalibrationRecordRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<CalibrationRecord> findByNo(String no) { return store.find(TYPE, no, CalibrationRecord.class); }
    public List<CalibrationRecord> findAll() { return store.findAll(TYPE, CalibrationRecord.class).stream()
            .sorted(Comparator.comparing(CalibrationRecord::updatedAt, newestFirst())).toList(); }
    public CalibrationRecord save(CalibrationRecord value) { return store.save(TYPE, value.calibrationNo(), value); }
    public long count() { return store.count(TYPE); }
}

@Repository
@Profile("server")
class OpenGaussConfigurationChangeRepository extends OpenGaussAdapterSupport implements ConfigurationChangeRepository {
    private static final String TYPE = "configuration-change";
    OpenGaussConfigurationChangeRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<ConfigurationChange> findByNo(String no) { return store.find(TYPE, no, ConfigurationChange.class); }
    public List<ConfigurationChange> findAll() { return store.findAll(TYPE, ConfigurationChange.class).stream()
            .sorted(Comparator.comparing(ConfigurationChange::updatedAt, newestFirst())).toList(); }
    public ConfigurationChange save(ConfigurationChange value) { return store.save(TYPE, value.changeNo(), value); }
    public long count() { return store.count(TYPE); }
}
