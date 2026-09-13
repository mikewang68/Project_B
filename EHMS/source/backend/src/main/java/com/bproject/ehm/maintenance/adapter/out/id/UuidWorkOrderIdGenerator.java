package com.bproject.ehm.maintenance.adapter.out.id;

import com.bproject.ehm.maintenance.ports.WorkOrderIdGenerator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Component
public class UuidWorkOrderIdGenerator implements WorkOrderIdGenerator {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneId.of("Asia/Shanghai"));

    @Override
    public String next(Instant now) {
        String suffix = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8).toUpperCase(Locale.ROOT);
        return "WO-" + DATE.format(now) + "-" + suffix;
    }
}
