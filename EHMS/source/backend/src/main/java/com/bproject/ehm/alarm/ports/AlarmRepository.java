package com.bproject.ehm.alarm.ports;

import com.bproject.ehm.alarm.application.AlarmMetrics;
import com.bproject.ehm.alarm.domain.model.Alarm;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;

import java.util.List;
import java.util.Optional;

public interface AlarmRepository {
    PageResult<Alarm> findAll(PageQuery page);

    Optional<Alarm> findByAlarmNo(String alarmNo);

    List<Alarm> findOpenByDeviceCode(String deviceCode, int limit);

    Alarm save(Alarm alarm);

    long countAll();

    AlarmMetrics metrics();
}
