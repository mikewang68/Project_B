package com.bproject.ehm.asset.ports;

import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;

import java.util.Optional;

public interface AssetRepository {
    PageResult<Asset> findActive(PageQuery page, String keyword, String area, String type);

    Optional<Asset> findByCode(String code);

    boolean existsByCode(String code);

    Asset save(Asset asset);

    long countActive();
}
