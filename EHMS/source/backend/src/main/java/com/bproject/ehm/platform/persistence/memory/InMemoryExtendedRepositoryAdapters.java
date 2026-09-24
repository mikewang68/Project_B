package com.bproject.ehm.platform.persistence.memory;

import com.bproject.ehm.asset.domain.model.CalibrationRecord;
import com.bproject.ehm.asset.domain.model.ConfigurationChange;
import com.bproject.ehm.asset.domain.model.DeviceTemplate;
import com.bproject.ehm.asset.ports.CalibrationRecordRepository;
import com.bproject.ehm.asset.ports.ConfigurationChangeRepository;
import com.bproject.ehm.asset.ports.DeviceTemplateRepository;
import com.bproject.ehm.workbench.domain.model.ShiftHandover;
import com.bproject.ehm.workbench.domain.model.UserTask;
import com.bproject.ehm.workbench.ports.ShiftHandoverRepository;
import com.bproject.ehm.workbench.ports.UserTaskRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryExtendedRepositoryAdapters { private InMemoryExtendedRepositoryAdapters() {} }

@Repository @Profile("!server")
class InMemoryUserTaskRepository implements UserTaskRepository {
    private final ConcurrentHashMap<String, UserTask> values = new ConcurrentHashMap<>();
    public Optional<UserTask> findByTaskNo(String no) { return Optional.ofNullable(values.get(no)); }
    public List<UserTask> findAll() { return new ArrayList<>(values.values()); }
    public UserTask save(UserTask value) { values.put(value.taskNo(), value); return value; }
    public long count() { return values.size(); }
}

@Repository @Profile("!server")
class InMemoryShiftHandoverRepository implements ShiftHandoverRepository {
    private final ConcurrentHashMap<String, ShiftHandover> values = new ConcurrentHashMap<>();
    public Optional<ShiftHandover> findByHandoverNo(String no) { return Optional.ofNullable(values.get(no)); }
    public List<ShiftHandover> findAll() { return new ArrayList<>(values.values()); }
    public ShiftHandover save(ShiftHandover value) { values.put(value.handoverNo(), value); return value; }
    public long count() { return values.size(); }
}

@Repository @Profile("!server")
class InMemoryDeviceTemplateRepository implements DeviceTemplateRepository {
    private final ConcurrentHashMap<String, DeviceTemplate> values = new ConcurrentHashMap<>();
    public Optional<DeviceTemplate> findByCode(String code) { return Optional.ofNullable(values.get(code)); }
    public List<DeviceTemplate> findAll() { return new ArrayList<>(values.values()); }
    public DeviceTemplate save(DeviceTemplate value) { values.put(value.templateCode(), value); return value; }
    public long count() { return values.size(); }
}

@Repository @Profile("!server")
class InMemoryCalibrationRecordRepository implements CalibrationRecordRepository {
    private final ConcurrentHashMap<String, CalibrationRecord> values = new ConcurrentHashMap<>();
    public Optional<CalibrationRecord> findByNo(String no) { return Optional.ofNullable(values.get(no)); }
    public List<CalibrationRecord> findAll() { return new ArrayList<>(values.values()); }
    public CalibrationRecord save(CalibrationRecord value) { values.put(value.calibrationNo(), value); return value; }
    public long count() { return values.size(); }
}

@Repository @Profile("!server")
class InMemoryConfigurationChangeRepository implements ConfigurationChangeRepository {
    private final ConcurrentHashMap<String, ConfigurationChange> values = new ConcurrentHashMap<>();
    public Optional<ConfigurationChange> findByNo(String no) { return Optional.ofNullable(values.get(no)); }
    public List<ConfigurationChange> findAll() { return new ArrayList<>(values.values()); }
    public ConfigurationChange save(ConfigurationChange value) { values.put(value.changeNo(), value); return value; }
    public long count() { return values.size(); }
}
