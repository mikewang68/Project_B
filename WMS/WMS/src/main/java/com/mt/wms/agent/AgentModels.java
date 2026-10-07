package com.mt.wms.agent;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class AgentModels {
    private AgentModels() {}
    public record Scope(long companyId,long warehouseId,long ownerId,long userId,boolean allOwners) {}
    public record ChatRequest(@NotBlank @Size(max=2000) String question,
                              @Size(max=8) List<@Size(max=2000) String> previousQuestions) {}
    public record Event(String tool,String label,Map<String,Object> arguments,Object result) {}
    public record ChatResult(long id,String answer,String state,List<Event> events,String model) {}
    public record ScheduleRequest(@NotBlank @Size(max=100) String name,boolean enabled,
            @NotBlank String frequency,@Min(5) @Max(10080) int intervalMinutes,
            @Pattern(regexp="(?:[01]\\d|2[0-3]):[0-5]\\d") String dailyTime,
            @Size(max=1000) String emails,boolean lowStock,boolean replenishment,boolean frozenStock,
            boolean capacity,@DecimalMin("1") @DecimalMax("150") BigDecimal capacityPercent,
            @Min(1) @Max(168) int cooldownHours) {}
    public record Schedule(long id,long companyId,long warehouseId,long ownerId,long userId,String name,
            boolean enabled,String frequency,int intervalMinutes,String dailyTime,String emails,
            boolean lowStock,boolean replenishment,boolean frozenStock,boolean capacity,
            BigDecimal capacityPercent,int cooldownHours,OffsetDateTime nextRunAt,
            OffsetDateTime lastRunAt,String lastError) {}
    public record Finding(String key,String kind,String title,String body,String level) {}
    public record WeightRequest(@NotNull @DecimalMin("0.001") BigDecimal weightKg) {}
}
