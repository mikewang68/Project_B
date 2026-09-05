package com.bproject.safety.support.dict;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 基础字典 Java Seed（本阶段不建字典表）。
 * 数据对齐前端各页面内联常量：ANALYTICS_AREAS / TEAMS / ALERT_ASSIGNEES 等。
 */
@Component
public class DictionarySeed {

    public List<String> areas() {
        return List.of("装卸区 A", "装卸区 B", "龙门吊作业区", "翻箱机区", "车辆通道", "临时施工区域");
    }

    public List<String> teams() {
        return List.of("装卸一班", "装卸二班", "安全管理组", "设备维保班");
    }

    public List<Assignee> assignees() {
        return List.of(
                new Assignee("USR-001", "李娜", "安全管理组"),
                new Assignee("USR-002", "王建国", "安全管理组"),
                new Assignee("USR-003", "赵明", "装卸一班"),
                new Assignee("USR-004", "陈静", "设备维保班"));
    }

    public record Assignee(String id, String name, String team) {
    }
}
