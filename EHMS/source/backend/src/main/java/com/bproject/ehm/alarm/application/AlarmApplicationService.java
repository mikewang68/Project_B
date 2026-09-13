package com.bproject.ehm.alarm.application;

import com.bproject.ehm.alarm.domain.model.Alarm;
import com.bproject.ehm.alarm.ports.AlarmRepository;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

@Service
public class AlarmApplicationService implements AlarmQueryFacade {
    private final AlarmRepository alarms;
    private final Clock clock;

    @Autowired
    public AlarmApplicationService(AlarmRepository alarms) {
        this(alarms, Clock.systemUTC());
    }

    AlarmApplicationService(AlarmRepository alarms, Clock clock) {
        this.alarms = alarms;
        this.clock = clock;
    }

    @Override
    public PageResult<AlarmView> list(PageQuery page) {
        return alarms.findAll(page).map(AlarmView::from);
    }

    @Override
    public List<AlarmView> findOpenByDeviceCode(String deviceCode, int limit) {
        return alarms.findOpenByDeviceCode(deviceCode, Math.max(1, Math.min(limit, 100))).stream()
                .map(AlarmView::from).toList();
    }

    public AlarmView acknowledge(String alarmNo, String operator) {
        Alarm current = find(alarmNo);
        return AlarmView.from(alarms.save(current.acknowledge(operator, clock.instant())));
    }

    public AlarmView close(String alarmNo, String operator, String reason) {
        Alarm current = find(alarmNo);
        return AlarmView.from(alarms.save(current.close(operator, reason, clock.instant())));
    }

    @Override
    public AlarmMetrics metrics() {
        return alarms.metrics();
    }

    private Alarm find(String alarmNo) {
        return alarms.findByAlarmNo(alarmNo)
                .orElseThrow(() -> new ResourceNotFoundException("未找到告警：" + alarmNo));
    }
}
