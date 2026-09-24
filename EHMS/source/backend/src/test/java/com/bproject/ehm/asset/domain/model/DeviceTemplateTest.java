package com.bproject.ehm.asset.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceTemplateTest {
    private static final Instant NOW = Instant.parse("2026-09-24T02:00:00Z");

    @Test void publishedTemplateIsLocked() {
        DeviceTemplate template = DeviceTemplate.create("tpl-1", "门吊模板", "门吊",
                List.of(new DeviceTemplate.TemplateComponent("HOIST", "起升机构", "传动", 1, true)),
                List.of(), "月检", "关键设备", "管理员", NOW).publish("设备主管", NOW.plusSeconds(30));
        assertEquals("PUBLISHED", template.status());
        assertThrows(DomainConflictException.class, () -> template.revise("新名称", "门吊",
                template.components(), template.measurementPoints(), "月检", "关键设备", NOW.plusSeconds(60)));
    }
}
