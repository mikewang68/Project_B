package com.bproject.ehm.asset.ports;

import com.bproject.ehm.asset.domain.model.MeasurementPoint;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MeasurementPointRepository {
    PageResult<MeasurementPoint> findActiveByAssetCode(String assetCode, PageQuery page, String keyword);

    List<MeasurementPoint> findActiveByCodes(Collection<String> codes);

    List<String> findActiveCodesByAssetCode(String assetCode);

    List<String> findEnabledCodesByAssetCode(String assetCode);

    Optional<MeasurementPoint> findByCode(String code);

    boolean existsByCode(String code);

    MeasurementPoint save(MeasurementPoint point);

    long countActiveByAssetCode(String assetCode);

    long countEnabledByAssetCode(String assetCode);

    long countActiveByComponentCode(String componentCode);
}
