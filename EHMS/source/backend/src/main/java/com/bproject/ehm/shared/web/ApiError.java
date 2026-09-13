package com.bproject.ehm.shared.web;

import java.time.Instant;
import java.util.List;

public record ApiError(
        String code,
        String message,
        List<String> details,
        String traceId,
        Instant timestamp
) {
}
