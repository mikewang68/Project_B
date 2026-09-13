package com.bproject.ehm.asset.application;

import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.asset.ports.AssetRepository;
import com.bproject.ehm.monitoring.application.MonitoringFacade;
import com.bproject.ehm.monitoring.domain.model.DeviceSnapshot;
import com.bproject.ehm.monitoring.domain.model.SnapshotMetrics;
import com.bproject.ehm.shared.error.DomainConflictException;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import com.bproject.ehm.shared.error.ValidationException;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DeviceApplicationService implements AssetQueryFacade {
    private final AssetRepository assets;
    private final MonitoringFacade monitoring;
    private final Clock clock;

    @Autowired
    public DeviceApplicationService(AssetRepository assets, MonitoringFacade monitoring) {
        this(assets, monitoring, Clock.systemUTC());
    }

    DeviceApplicationService(AssetRepository assets, MonitoringFacade monitoring, Clock clock) {
        this.assets = assets;
        this.monitoring = monitoring;
        this.clock = clock;
    }

    @Override
    public PageResult<DeviceView> list(PageQuery page, String keyword, String area, String type) {
        PageResult<Asset> assetPage = assets.findActive(page, keyword, area, type);
        Map<String, DeviceSnapshot> snapshots = monitoring.findSnapshots(
                        assetPage.content().stream().map(Asset::code).toList()).stream()
                .collect(Collectors.toMap(DeviceSnapshot::deviceCode, Function.identity()));
        return assetPage.map(asset -> DeviceView.compose(asset, snapshots.get(asset.code())));
    }

    @Override
    public DeviceView get(String code) {
        Asset asset = findAsset(code);
        return DeviceView.compose(asset, monitoring.findSnapshot(asset.code()).orElse(null));
    }

    @Override
    public boolean exists(String code) {
        return assets.existsByCode(Asset.normalizeCode(code));
    }

    public DeviceView create(DeviceCommand command) {
        try {
            Instant now = clock.instant();
            Asset asset = Asset.create(command.code(), command.name(), command.type(), command.area(),
                    command.maintenanceDate(), command.owner(), now);
            if (assets.existsByCode(asset.code())) {
                throw new DomainConflictException("设备编码已存在：" + asset.code());
            }
            Asset saved = assets.save(asset);
            DeviceSnapshot snapshot = monitoring.saveSnapshot(DeviceSnapshot.initial(saved.code(),
                    command.condition(), command.health(), command.risk(), command.riskClass(), command.quality(),
                    command.ready(), command.alarm(), command.temperature(), command.vibration(), command.current(),
                    command.rulDays(), now));
            return DeviceView.compose(saved, snapshot);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public DeviceView update(String code, DeviceCommand command) {
        try {
            Instant now = clock.instant();
            Asset current = findAsset(code);
            Asset saved = assets.save(current.update(command.name(), command.type(), command.area(),
                    command.maintenanceDate(), command.owner(), now));
            DeviceSnapshot currentSnapshot = monitoring.findSnapshot(saved.code())
                    .orElseGet(() -> DeviceSnapshot.initial(saved.code(), null, null, null, null,
                            null, null, null, null, null, null, null, now));
            DeviceSnapshot snapshot = monitoring.saveSnapshot(currentSnapshot.update(command.condition(),
                    command.health(), command.risk(), command.riskClass(), command.quality(), command.ready(),
                    command.alarm(), command.temperature(), command.vibration(), command.current(), command.rulDays(), now));
            return DeviceView.compose(saved, snapshot);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public void archive(String code) {
        Instant now = clock.instant();
        Asset saved = assets.save(findAsset(code).archive(now));
        monitoring.findSnapshot(saved.code()).ifPresent(snapshot -> monitoring.saveSnapshot(snapshot.archived(now)));
    }

    @Override
    public AssetMetrics metrics() {
        SnapshotMetrics snapshot = monitoring.metrics();
        return new AssetMetrics(assets.countActive(), snapshot.online(), snapshot.assessable(), snapshot.healthy(),
                snapshot.highRisk(), snapshot.averageHealth(), snapshot.averageQuality());
    }

    private Asset findAsset(String code) {
        String normalized = Asset.normalizeCode(code);
        return assets.findByCode(normalized)
                .filter(asset -> !asset.archived())
                .orElseThrow(() -> new ResourceNotFoundException("未找到设备：" + normalized));
    }
}
