package com.bproject.ehm.asset.application;

import com.bproject.ehm.asset.domain.model.MeasurementPoint;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;

import java.util.Collection;
import java.util.List;

public interface MeasurementPointQueryFacade {
    PageResult<MeasurementPoint> page(String assetCode, PageQuery page, String keyword);

    MeasurementPoint getDomain(String code);

    List<MeasurementPoint> findByCodes(Collection<String> codes);

    List<String> activeCodes(String assetCode);

    List<String> enabledCodes(String assetCode);

    long countActive(String assetCode);

    long countEnabled(String assetCode);
}
