package com.bproject.trust.events;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record EventInput(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String sourceSystem,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,120}") String sourceEventId,
    @NotBlank @Pattern(regexp = "[A-Z_]{1,40}") String eventType,
    @NotBlank @Size(max = 120) String businessObjectId,
    @NotBlank @Size(max = 120) String batchId,
    @NotNull Instant occurredAt,
    @Size(max = 80) List<@NotBlank @Size(max = 120) String> bundleIds,
    @Size(max = 120) String handoverId,
    @Size(max = 80) List<@NotBlank @Size(max = 120) String> relatedBatchIds,
    @Size(max = 80)
        List<@Pattern(regexp = "[A-Za-z0-9._-]{1,80}:[A-Za-z0-9._-]{1,120}") String>
            relatedEventRefs,
    @DecimalMin("0") @Digits(integer = 15, fraction = 6) BigDecimal quantity,
    @Size(max = 20) String unit,
    @Size(max = 200) String location,
    @Size(max = 200) String supplier,
    @Size(max = 200) String receiver,
    @Size(max = 20) List<@Pattern(regexp = "[a-f0-9-]{36}") String> evidenceIds,
    @Size(max = 30) Map<@Size(max = 80) String, @Size(max = 2000) String> details) {
  public EventInput normalized() {
    return new EventInput(
        sourceSystem,
        sourceEventId,
        eventType,
        businessObjectId,
        batchId,
        occurredAt,
        sorted(bundleIds),
        handoverId,
        sorted(relatedBatchIds),
        sorted(relatedEventRefs),
        quantity == null ? null : quantity.stripTrailingZeros(),
        unit,
        location,
        supplier,
        receiver,
        sorted(evidenceIds),
        details == null ? Map.of() : new TreeMap<>(details));
  }

  private static List<String> sorted(List<String> values) {
    return values == null ? List.of() : values.stream().distinct().sorted().toList();
  }
}
