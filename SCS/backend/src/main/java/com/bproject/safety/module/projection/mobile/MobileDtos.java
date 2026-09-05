package com.bproject.safety.module.projection.mobile;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 移动端首页投影 DTO（聚合自 Alert 权威源，不建独立 MobileIncident 数据源）。
 * 列表/详情仍复用 /api/v1/alerts，由前端 MobileAlertAdapter 转成手机端 ViewModel。
 */
public final class MobileDtos {

    private MobileDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MobileHomeItem(
            String id,
            String title,
            String risk,
            String area,
            String target,
            String time,
            String status,
            String mobileStage,
            String assignee,
            String occurredAt) {
    }

    public record MobileHome(
            String userName,
            String role,
            String team,
            String shift,
            int pending,
            int handling,
            int urgent,
            int closedToday,
            List<MobileHomeItem> pendingItems,
            List<MobileHomeItem> handlingItems) {
    }
}
