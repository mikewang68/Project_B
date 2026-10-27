package com.mt.wms.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

final class AgentMath {
    private AgentMath() {}
    static BigDecimal percent(BigDecimal amount,BigDecimal capacity,boolean complete) {
        if (!complete || capacity==null || capacity.signum()<=0) return null;
        return amount.multiply(BigDecimal.valueOf(100)).divide(capacity,2,RoundingMode.HALF_UP);
    }
    static BigDecimal itemWeight(BigDecimal quantity,BigDecimal unitWeight,String unit) {
        if (quantity.signum()==0) return BigDecimal.ZERO;
        if (Set.of("kg","千克","公斤").contains(unit.toLowerCase(Locale.ROOT))) return quantity;
        if (Set.of("t","吨").contains(unit.toLowerCase(Locale.ROOT))) return quantity.multiply(BigDecimal.valueOf(1000));
        if (unitWeight==null || unitWeight.signum()<=0) return null;
        return quantity.multiply(unitWeight); // 钢材“捆”的 weight_kg 表示每捆重量。
    }
    static OffsetDateTime nextRun(String frequency,int minutes,String dailyTime,Clock clock,ZoneId zone) {
        ZonedDateTime now=ZonedDateTime.now(clock).withZoneSameInstant(zone);
        if ("INTERVAL".equals(frequency)) return now.plusMinutes(minutes).toOffsetDateTime();
        if (!"DAILY".equals(frequency)) throw new IllegalArgumentException("频率只能选择 DAILY 或 INTERVAL");
        ZonedDateTime next=now.toLocalDate().atTime(LocalTime.parse(dailyTime)).atZone(zone);
        if (!next.isAfter(now)) next=next.plusDays(1);
        return next.toOffsetDateTime();
    }
    static List<String> keywords(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for (Object v:list) {
            String s=String.valueOf(v).trim().replace("%","").replace("_","").replace("\\","");
            if (!s.isBlank() && s.length()<=64) result.add(s);
            if (result.size()>=8) break;
        }
        if (result.contains("钢材") || result.contains("钢铁")) result.addAll(List.of("钢","螺纹","型材","Q235","Q355"));
        return List.copyOf(result);
    }
}
