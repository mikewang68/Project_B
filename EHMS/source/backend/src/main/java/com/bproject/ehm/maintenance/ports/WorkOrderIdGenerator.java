package com.bproject.ehm.maintenance.ports;

import java.time.Instant;

public interface WorkOrderIdGenerator {
    String next(Instant now);
}
