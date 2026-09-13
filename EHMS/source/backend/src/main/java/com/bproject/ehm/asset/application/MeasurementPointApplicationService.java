package com.bproject.ehm.asset.application;

import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.asset.domain.model.Component;
import com.bproject.ehm.asset.domain.model.MeasurementPoint;
import com.bproject.ehm.asset.ports.ComponentRepository;
import com.bproject.ehm.asset.ports.MeasurementPointRepository;
import com.bproject.ehm.shared.error.DomainConflictException;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import com.bproject.ehm.shared.error.ValidationException;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collection;
import java.util.List;

@Service
public class MeasurementPointApplicationService implements MeasurementPointQueryFacade {
    private final MeasurementPointRepository points;
    private final ComponentRepository components;
    private final AssetQueryFacade assets;
    private final Clock clock;

    @Autowired
    public MeasurementPointApplicationService(MeasurementPointRepository points, ComponentRepository components,
                                              AssetQueryFacade assets) {
        this(points, components, assets, Clock.systemUTC());
    }

    MeasurementPointApplicationService(MeasurementPointRepository points, ComponentRepository components,
                                       AssetQueryFacade assets, Clock clock) {
        this.points = points;
        this.components = components;
        this.assets = assets;
        this.clock = clock;
    }

    public PageResult<MeasurementPointView> list(String assetCode, PageQuery page, String keyword) {
        return page(assetCode, page, keyword).map(MeasurementPointView::from);
    }

    @Override
    public PageResult<MeasurementPoint> page(String assetCode, PageQuery page, String keyword) {
        String normalizedAsset = Asset.normalizeCode(assetCode);
        requireAsset(normalizedAsset);
        return points.findActiveByAssetCode(normalizedAsset, page, keyword);
    }

    public MeasurementPointView get(String code) {
        return MeasurementPointView.from(getDomain(code));
    }

    public MeasurementPointView create(String assetCode, MeasurementPointCommand command) {
        try {
            String normalizedAsset = Asset.normalizeCode(assetCode);
            requireAsset(normalizedAsset);
            requireComponent(normalizedAsset, command.componentCode());
            MeasurementPoint point = MeasurementPoint.create(command.code(), normalizedAsset,
                    command.componentCode(), command.name(), command.metric(), command.unit(),
                    command.sourceProtocol(), command.sourceAddress(), command.sampleIntervalSeconds(),
                    command.lowerLimit(), command.upperLimit(), command.enabled(), clock.instant());
            if (points.existsByCode(point.code())) throw new DomainConflictException("测点编码已存在：" + point.code());
            return MeasurementPointView.from(points.save(point));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public MeasurementPointView update(String code, MeasurementPointCommand command) {
        try {
            MeasurementPoint current = getDomain(code);
            requireComponent(current.assetCode(), command.componentCode());
            MeasurementPoint changed = current.update(command.componentCode(), command.name(), command.metric(),
                    command.unit(), command.sourceProtocol(), command.sourceAddress(),
                    command.sampleIntervalSeconds(), command.lowerLimit(), command.upperLimit(),
                    command.enabled(), clock.instant());
            return MeasurementPointView.from(points.save(changed));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public void archive(String code) {
        points.save(getDomain(code).archive(clock.instant()));
    }

    @Override
    public MeasurementPoint getDomain(String code) {
        String normalized = MeasurementPoint.normalizeCode(code);
        return points.findByCode(normalized).filter(point -> !point.archived())
                .orElseThrow(() -> new ResourceNotFoundException("未找到测点：" + normalized));
    }

    @Override
    public List<MeasurementPoint> findByCodes(Collection<String> codes) {
        return points.findActiveByCodes(codes);
    }

    @Override
    public List<String> activeCodes(String assetCode) {
        return points.findActiveCodesByAssetCode(Asset.normalizeCode(assetCode));
    }

    @Override
    public List<String> enabledCodes(String assetCode) {
        return points.findEnabledCodesByAssetCode(Asset.normalizeCode(assetCode));
    }

    @Override
    public long countActive(String assetCode) {
        return points.countActiveByAssetCode(Asset.normalizeCode(assetCode));
    }

    @Override
    public long countEnabled(String assetCode) {
        return points.countEnabledByAssetCode(Asset.normalizeCode(assetCode));
    }

    private void requireAsset(String assetCode) {
        if (!assets.exists(assetCode)) throw new ResourceNotFoundException("未找到设备：" + assetCode);
    }

    private void requireComponent(String assetCode, String componentCode) {
        String normalized = Component.normalizeCode(componentCode);
        Component component = components.findByCode(normalized)
                .filter(value -> !value.archived())
                .orElseThrow(() -> new ResourceNotFoundException("未找到部件：" + normalized));
        if (!assetCode.equals(component.assetCode())) throw new DomainConflictException("测点部件不属于目标设备");
    }
}
