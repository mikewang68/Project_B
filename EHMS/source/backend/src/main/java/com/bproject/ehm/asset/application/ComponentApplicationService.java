package com.bproject.ehm.asset.application;

import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.asset.domain.model.Component;
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
import java.time.Instant;

@Service
public class ComponentApplicationService {
    private final ComponentRepository components;
    private final AssetQueryFacade assets;
    private final MeasurementPointRepository measurementPoints;
    private final Clock clock;

    @Autowired
    public ComponentApplicationService(ComponentRepository components, AssetQueryFacade assets,
                                       MeasurementPointRepository measurementPoints) {
        this(components, assets, measurementPoints, Clock.systemUTC());
    }

    ComponentApplicationService(ComponentRepository components, AssetQueryFacade assets,
                                MeasurementPointRepository measurementPoints, Clock clock) {
        this.components = components;
        this.assets = assets;
        this.measurementPoints = measurementPoints;
        this.clock = clock;
    }

    public PageResult<ComponentView> list(String assetCode, PageQuery page, String keyword) {
        String normalizedAsset = Asset.normalizeCode(assetCode);
        requireAsset(normalizedAsset);
        return components.findActiveByAssetCode(normalizedAsset, page, keyword).map(ComponentView::from);
    }

    public ComponentView get(String code) {
        return ComponentView.from(findActive(code));
    }

    public ComponentView create(String assetCode, ComponentCommand command) {
        try {
            String normalizedAsset = Asset.normalizeCode(assetCode);
            requireAsset(normalizedAsset);
            Instant now = clock.instant();
            Component component = Component.create(command.code(), normalizedAsset, command.parentCode(), command.name(),
                    command.category(), command.manufacturer(), command.model(), command.serialNumber(),
                    command.criticality(), command.position(), command.installedOn(), command.status(), now);
            if (components.existsByCode(component.code())) {
                throw new DomainConflictException("部件编码已存在：" + component.code());
            }
            validateParent(component.assetCode(), component.code(), component.parentCode());
            return ComponentView.from(components.save(component));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public ComponentView update(String code, ComponentCommand command) {
        try {
            Component current = findActive(code);
            validateParent(current.assetCode(), current.code(), command.parentCode());
            Component changed = current.update(command.parentCode(), command.name(), command.category(),
                    command.manufacturer(), command.model(), command.serialNumber(), command.criticality(),
                    command.position(), command.installedOn(), command.status(), clock.instant());
            return ComponentView.from(components.save(changed));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public void archive(String code) {
        Component current = findActive(code);
        if (measurementPoints.countActiveByComponentCode(current.code()) > 0) {
            throw new DomainConflictException("部件仍有关联测点，不能直接归档：" + current.code());
        }
        components.save(current.archive(clock.instant()));
    }

    private void validateParent(String assetCode, String componentCode, String parentCode) {
        if (parentCode == null || parentCode.isBlank()) return;
        String normalizedParent = Component.normalizeCode(parentCode);
        if (componentCode.equals(normalizedParent)) throw new ValidationException("部件不能以自身作为上级");
        Component parent = findActive(normalizedParent);
        if (!assetCode.equals(parent.assetCode())) throw new DomainConflictException("上级部件不属于同一设备");
    }

    private Component findActive(String code) {
        String normalized = Component.normalizeCode(code);
        return components.findByCode(normalized)
                .filter(component -> !component.archived())
                .orElseThrow(() -> new ResourceNotFoundException("未找到部件：" + normalized));
    }

    private void requireAsset(String assetCode) {
        if (!assets.exists(assetCode)) throw new ResourceNotFoundException("未找到设备：" + assetCode);
    }
}
