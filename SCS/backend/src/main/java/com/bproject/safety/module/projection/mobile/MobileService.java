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

    private final AlertRepository repository;

    public MobileService(AlertRepository repository) {
        this.repository = repository;
    }

    public MobileHome home() {
        List<DemoAlert> all = repository.findAll();
        List<DemoAlert> pending = all.stream()
                .filter(a -> AlertStatuses.TO_HANDLE.equals(a.status))
                .sorted(occurredDesc()).toList();
        List<DemoAlert> handling = all.stream()
                .filter(a -> AlertStatuses.HANDLING.equals(a.status) || AlertStatuses.ESCALATED.equals(a.status))
                .sorted(occurredDesc()).toList();
        int urgent = (int) all.stream()
                .filter(a -> "紧急".equals(a.risk) && !AlertStatuses.CLOSED.equals(a.status)).count();
        int closed = (int) all.stream().filter(a -> AlertStatuses.CLOSED.equals(a.status)).count();

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
        return new MobileHomeItem(a.id, a.title, a.risk, a.area, a.target, a.time,
                a.status, a.mobileStage, a.assignee,
                a.occurredAt == null ? null : ISO.format(a.occurredAt));
    }
}
