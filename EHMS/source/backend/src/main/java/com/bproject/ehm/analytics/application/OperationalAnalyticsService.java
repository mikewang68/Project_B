package com.bproject.ehm.analytics.application;

import com.bproject.ehm.asset.application.AssetQueryFacade;
import com.bproject.ehm.asset.application.DeviceView;
import com.bproject.ehm.alarm.application.AlarmQueryFacade;
import com.bproject.ehm.alarm.application.AlarmView;
import com.bproject.ehm.maintenance.application.WorkOrderQueryFacade;
import com.bproject.ehm.maintenance.application.WorkOrderView;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only analytics over application facades: no adapter or database coupling. */
@Service
public class OperationalAnalyticsService {
    private final AssetQueryFacade assets;
    private final AlarmQueryFacade alarms;
    private final WorkOrderQueryFacade orders;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public OperationalAnalyticsService(AssetQueryFacade assets, AlarmQueryFacade alarms, WorkOrderQueryFacade orders) {
        this(assets, alarms, orders, Clock.systemUTC());
    }
    OperationalAnalyticsService(AssetQueryFacade assets, AlarmQueryFacade alarms, WorkOrderQueryFacade orders, Clock clock) {
        this.assets=assets; this.alarms=alarms; this.orders=orders; this.clock=clock;
    }
    public AnalyticsView overview(int days) {
        if (days < 1 || days > 366) throw new IllegalArgumentException("统计天数为1～366");
        List<DeviceView> devices = readAll(p -> assets.list(p, null, null, null));
        List<AlarmView> alarmItems = readAll(alarms::list);
        List<WorkOrderView> workItems = readAll(orders::list);
        ZoneId zone=ZoneId.of("Asia/Shanghai");
        LocalDate end=LocalDate.now(clock.withZone(zone));
        LocalDate start=end.minusDays(days-1);
        List<DailyEvent> series=new ArrayList<>();
        for(LocalDate date=start; !date.isAfter(end); date=date.plusDays(1)) {
            LocalDate selected=date;
            series.add(new DailyEvent(date.toString(), alarmItems.stream().filter(a -> onDate(a.occurredAt(), selected, zone)).count(),
                    workItems.stream().filter(w -> onDate(w.createdAt(),selected,zone)).count(),
                    workItems.stream().filter(w -> w.history()!=null && w.history().stream().anyMatch(
                            h -> h.to()!=null && "已关闭".equals(h.to().label()) && onDate(h.occurredAt(), selected, zone))).count()));
        }
        Map<String,Long> health=new LinkedHashMap<>();
        for(String grade:List.of("健康 80–100","关注 60–79","异常 0–59","未登记评分")) health.put(grade,0L);
        devices.forEach(d -> health.compute(d.health()==null?"未登记评分":d.health()>=80?"健康 80–100":d.health()>=60?"关注 60–79":"异常 0–59",(k,v)->v+1));
        return new AnalyticsView("openGauss", clock.instant(), start.toString(), end.toString(), devices.size(),
                alarmItems.size(),workItems.size(), health,group(devices,DeviceView::area),group(devices,DeviceView::type),
                group(alarmItems,AlarmView::status),group(workItems,WorkOrderView::status),series,
                "按业务记录的创建/关闭时间统计事件；0表示该日没有记录，不代表设备没有故障。健康分布来自台账评分，不代表实时自动评估。数据库可包含初始化样例，请以现场接入验收为准。");
    }
    private static boolean onDate(Instant time, LocalDate date, ZoneId zone) {return time!=null && time.atZone(zone).toLocalDate().equals(date);}
    private static <T> Map<String,Long> group(List<T> rows, Function<T,String> key) {
        return rows.stream().collect(Collectors.groupingBy(r -> Objects.requireNonNullElse(key.apply(r),"未分类"),TreeMap::new,Collectors.counting()));
    }
    private static <T> List<T> readAll(Function<PageQuery,PageResult<T>> query) {
        List<T> rows=new ArrayList<>(); int index=0; PageResult<T> page;
        do {page=query.apply(new PageQuery(index++,200));rows.addAll(page.content());}while(index<page.totalPages());
        return rows;
    }
    public record DailyEvent(String date,long alarmsCreated,long workOrdersCreated,long workOrdersClosed) {}
    public record AnalyticsView(String source,Instant calculatedAt,String from,String to,long devices,long alarms,long workOrders,
            Map<String,Long> healthDistribution,Map<String,Long> areaDistribution,Map<String,Long> typeDistribution,
            Map<String,Long> alarmStatus,Map<String,Long> workOrderStatus,List<DailyEvent> dailyEvents,String interpretation) {}
}
