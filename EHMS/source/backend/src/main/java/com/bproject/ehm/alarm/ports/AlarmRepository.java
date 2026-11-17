package com.bproject.ehm.alarm.ports;

import com.bproject.ehm.alarm.application.AlarmMetrics;
import com.bproject.ehm.alarm.domain.model.Alarm;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;

import java.util.List;
import java.util.Optional;

public interface AlarmRepository {
    PageResult<Alarm> findAll(PageQuery page);

    default PageResult<Alarm> search(PageQuery page,String keyword,String level,String status,String category){
        var values=new java.util.ArrayList<Alarm>();int n=0;PageResult<Alarm> batch;
        do{batch=findAll(new PageQuery(n++,200));values.addAll(batch.content());}while(n<batch.totalPages());
        String key=keyword==null?"":keyword.trim().toLowerCase(java.util.Locale.ROOT);
        var matching=values.stream().filter(a->key.isEmpty()||java.util.stream.Stream.of(a.alarmNo(),a.deviceCode(),a.deviceName(),a.component(),a.summary())
                .anyMatch(v->v!=null&&v.toLowerCase(java.util.Locale.ROOT).contains(key)))
            .filter(a->level==null||level.isBlank()||level.equals(a.level()))
            .filter(a->status==null||status.isBlank()||status.equals(a.status().name())||status.equals(a.status().label()))
            .filter(a->category==null||category.isBlank()||category.equals(categoryOf(a)))
            .sorted(java.util.Comparator.comparing(Alarm::occurredAt,java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))).toList();
        int from=(int)Math.min((long)page.page()*page.size(),matching.size());
        return PageResult.of(matching.subList(from,Math.min(from+page.size(),matching.size())),page,matching.size());
    }
    static String categoryOf(Alarm a){return "PUBLIC_SIM_DATA_QUALITY".equals(a.triggerMethod())?"DATA_QUALITY":
        a.alarmNo().startsWith("SAMPLE-")||String.valueOf(a.triggerMethod()).contains("模拟")?"SIMULATED_RULE":"RULE_EVENT";}

    Optional<Alarm> findByAlarmNo(String alarmNo);

    List<Alarm> findOpenByDeviceCode(String deviceCode, int limit);

    Alarm save(Alarm alarm);

    long countAll();

    AlarmMetrics metrics();
}
