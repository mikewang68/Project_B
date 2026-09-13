package com.bproject.ehm.asset.application;

import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;

public interface AssetQueryFacade {
    PageResult<DeviceView> list(PageQuery page, String keyword, String area, String type);

    DeviceView get(String code);

    boolean exists(String code);

    AssetMetrics metrics();
}
