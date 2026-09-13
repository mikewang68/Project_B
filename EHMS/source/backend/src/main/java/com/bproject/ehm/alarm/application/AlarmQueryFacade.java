package com.bproject.ehm.alarm.application;

import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;

import java.util.List;

public interface AlarmQueryFacade {
    PageResult<AlarmView> list(PageQuery page);

    List<AlarmView> findOpenByDeviceCode(String deviceCode, int limit);

    AlarmMetrics metrics();
}
