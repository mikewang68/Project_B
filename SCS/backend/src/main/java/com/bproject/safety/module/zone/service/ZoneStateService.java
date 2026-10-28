package com.bproject.safety.module.zone.service;

import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.common.realtime.LiveEventTypes;
import com.bproject.safety.module.zone.model.ZoneSnapshot;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 装卸区状态（内存态，由设备上报驱动）。
 *
 * <p>为什么不落库：装卸区状态是秒级刷新的实时量，重启后由网关在数秒内重新上报，
 * 落库既没有查询价值，还会把"最后一次上报"误当成当前状态。这里只保留最新快照。</p>
 *
 * <p>未收到上报时保持 {@link ZoneSnapshot#unknown}，前端显示"未接入"而不是"无人/无车"——
 * 两者语义完全不同，不能混。</p>
 */
@Service
public class ZoneStateService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 已知区域与其传感方案；不在表内的区域编码一律拒绝。 */
    private static final Map<String, String[]> ZONES = new LinkedHashMap<>();
    static {
        ZONES.put("LOADING-A", new String[]{"装卸区 A", "限位开关"});
        ZONES.put("LOADING-B", new String[]{"装卸区 B", "位置传感器"});
    }

    private final Map<String, ZoneSnapshot> states = new LinkedHashMap<>();
    private final DomainLivePublisher publisher;
    private final Clock clock;

    public ZoneStateService(DomainLivePublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
        ZONES.forEach((code, meta) -> states.put(code, ZoneSnapshot.unknown(code, meta[0], meta[1])));
    }

    public synchronized List<ZoneSnapshot> list() {
        return new ArrayList<>(states.values());
    }

    /**
     * 应用一次区域上报。字段为 null 表示本次没有该信号，保留上一次的值。
     *
     * @return 是否命中已知区域；未知编码返回 false，调用方据此回传 ignored
     */
    public synchronized boolean apply(String code, Boolean personnelWorking, Boolean vehiclePresent,
                                      Integer distanceMm, String signal) {
        if (code == null) {
            return false;
        }
        ZoneSnapshot current = states.get(code.trim());
        if (current == null) {
            return false;
        }
        ZoneSnapshot next = new ZoneSnapshot(
                current.code(),
                current.name(),
                current.sensorType(),
                personnelWorking == null ? current.personnelWorking() : personnelWorking,
                vehiclePresent == null ? current.vehiclePresent() : vehiclePresent,
                distanceMm == null ? current.distanceMm() : clampDistance(distanceMm),
                signal == null || signal.isBlank() ? current.signal() : clip(signal),
                OffsetDateTime.now(clock.withZone(ZONE)).toString(),
                true);
        states.put(next.code(), next);
        // 用 LinkedHashMap 而非 Map.of：B 区没有人员信号时 personnelWorking 为 null，Map.of 会抛 NPE
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("code", next.code());
        if (next.personnelWorking() != null) {
            event.put("personnelWorking", next.personnelWorking());
        }
        if (next.vehiclePresent() != null) {
            event.put("vehiclePresent", next.vehiclePresent());
        }
        publisher.publish(LiveEventTypes.ZONE_CHANGED, event);
        return true;
    }

    /** 位置传感器量程 0~10m，超出视为无效读数（保留上限避免前端画出荒谬距离）。 */
    private static Integer clampDistance(Integer mm) {
        if (mm == null || mm < 0) {
            return null;
        }
        return Math.min(mm, 10_000);
    }

    private static String clip(String value) {
        String t = value.trim();
        return t.length() <= 64 ? t : t.substring(0, 64);
    }
}
