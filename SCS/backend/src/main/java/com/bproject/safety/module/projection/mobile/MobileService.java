package com.bproject.safety.module.projection.mobile;

import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.projection.mobile.MobileDtos.MobileHome;
import com.bproject.safety.module.projection.mobile.MobileDtos.MobileHomeItem;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 移动端首页聚合：仅对同一批 Alert 做投影与计数，不维护独立状态。
 * 现场处置阶段（已接单/已到场）通过 DemoAlert.mobileStage 承接，主状态机不变。
 */
@Service
public class MobileService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** 移动端“处置中”口径：处理中 / 已升级（待复核属于复核队列，不计入移动端处置中）。 */
    private static final java.util.Set<String> IN_HANDLING = java.util.Set.of(
            AlertStatuses.PROCESSING, AlertStatuses.ESCALATED);

    private final AlertRepository repository;

    public MobileService(AlertRepository repository) {
        this.repository = repository;
    }

    public MobileHome home() {
        List<DemoAlert> all = repository.findAll();
        List<DemoAlert> pending = all.stream()
                .filter(a -> AlertStatuses.PENDING_PROCESS.equals(a.statusCode))
                .sorted(occurredDesc()).toList();
        List<DemoAlert> handling = all.stream()
                .filter(a -> IN_HANDLING.contains(a.statusCode))
                .sorted(occurredDesc()).toList();
        int urgent = (int) all.stream()
                .filter(a -> com.bproject.safety.module.alert.model.RiskLevels.URGENT.equals(a.riskCode)
                        && !AlertStatuses.CLOSED.equals(a.statusCode)).count();
        int closed = (int) all.stream().filter(a -> AlertStatuses.CLOSED.equals(a.statusCode)).count();

        return new MobileHome(
                "王建国", "安全员", "装卸一班", "夜班",
                pending.size(), handling.size(), urgent, closed,
                pending.stream().map(this::toItem).toList(),
                handling.stream().map(this::toItem).toList());
    }

    private Comparator<DemoAlert> occurredDesc() {
        return Comparator.comparing((DemoAlert a) -> a.occurredAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(a -> a.id, Comparator.reverseOrder());
    }

    private MobileHomeItem toItem(DemoAlert a) {
        return new MobileHomeItem(a.id, a.title, a.getRisk(), a.area, a.target, a.time,
                a.getStatus(), a.mobileStage, a.assignee,
                a.occurredAt == null ? null : ISO.format(a.occurredAt));
    }
}
