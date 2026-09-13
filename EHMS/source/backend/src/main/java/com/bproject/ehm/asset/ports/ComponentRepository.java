package com.bproject.ehm.asset.ports;

import com.bproject.ehm.asset.domain.model.Component;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;

import java.util.Optional;

public interface ComponentRepository {
    PageResult<Component> findActiveByAssetCode(String assetCode, PageQuery page, String keyword);

    Optional<Component> findByCode(String code);

    boolean existsByCode(String code);

    Component save(Component component);

    long countActiveByAssetCode(String assetCode);
}
