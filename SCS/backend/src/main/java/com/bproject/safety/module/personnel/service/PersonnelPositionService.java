package com.bproject.safety.module.personnel.service;

import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.common.realtime.LiveEventTypes;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 人员实时位置（内存态，由 UWB 定位引擎经网关上报驱动）。
 *
 * <p><b>为什么不落库</b>：位置是 1~5Hz 的实时量，每秒写一次 {@code safety_personnel} 既没有
 * 查询价值，还会把"最后一次上报"误当成当前状态——与 {@link com.bproject.safety.module.zone.service.ZoneStateService}
 * 同一取舍。这里只保留每个人员的最新坐标，重启后由网关在数秒内重新上报。</p>
 *
 * <p><b>与台账的关系</b>：人员台账（姓名/班组/手环）仍是权威源，上报只覆盖坐标、区域、
 * 电量与定位质量这几个由定位系统产生的字段。没上报过的人员沿用台账坐标，页面不需要
 * 区分"未接入"——人员名单本来就是全员口径，不像区域状态需要表达"未接入"。</p>
 */
@Service
public class PersonnelPositionService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 定位精度 → 定位质量档位（米）。阈值是我方构造，现场按 UWB 引擎标称精度调整。 */
    private static final double QUALITY_EXCELLENT_M = 0.30;
    private static final double QUALITY_GOOD_M = 0.80;

    /**
     * 站场区域矩形（百分比坐标）。
     *
     * <p>与首页底图 {@code frontend/src/styles/overview.scss} 的 {@code .zone-*} 保持一致；
     * 底图区域调整时这里要同步，否则人员会被归到错误的区域名称。</p>
     */
    private static final AreaRect[] AREA_RECTS = {
            new AreaRect(5, 10, 33, 36, "装卸区 A"),
            new AreaRect(40, 7, 65, 37, "龙门吊作业区"),
            new AreaRect(70, 10, 95, 36, "装卸区 B"),
            new AreaRect(7, 69, 30, 92, "临时施工区域"),
            new AreaRect(68, 67, 94, 92, "翻箱机区"),
    };

    /** 未命中任何作业区时归入车辆通道（底图上剩余的可通行区域）。 */
    private static final String AREA_FALLBACK = "车辆通道";

    private record AreaRect(double x1, double y1, double x2, double y2, String area) {
        boolean contains(double x, double y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }
    }

    /** 一次上报解析后的位置快照。 */
    public record LivePosition(double x, double y, String area, Integer battery,
                               String quality, String updatedAt) {
    }

    private final Map<String, LivePosition> positions = new ConcurrentHashMap<>();
    private final PersonnelRepository repository;
    private final DomainLivePublisher publisher;
    private final Clock clock;

    public PersonnelPositionService(PersonnelRepository repository, DomainLivePublisher publisher, Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
    }

    public LivePosition get(String id) {
        return id == null ? null : positions.get(id);
    }

    /**
     * 应用一次人员位置上报。
     *
     * @return 是否命中已登记人员；未知/已删除人员返回 false，调用方据此回传 ignored。
     *         上报方不能成为第二条人员主数据来源，不允许新建人员。
     */
    public boolean apply(String id, Double x, Double y, Integer battery, Double accuracy) {
        if (id == null || id.isBlank() || x == null || y == null) {
            return false;
        }
        String key = id.trim();
        var person = repository.findById(key).orElse(null);
        if (person == null || person.isDeleted) {
            return false;
        }
        double px = clampPercent(x);
        double py = clampPercent(y);
        positions.put(key, new LivePosition(
                round(px),
                round(py),
                resolveArea(px, py),
                battery == null ? null : Math.max(0, Math.min(100, battery)),
                qualityOf(accuracy),
                OffsetDateTime.now(clock.withZone(ZONE)).toString()));
        return true;
    }

    /** 一批上报应用完成后统一广播一次，避免 1~5Hz × N 人把 WS 打成事件风暴。 */
    public void announceMoved(List<String> appliedIds) {
        if (appliedIds == null || appliedIds.isEmpty()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", appliedIds.size());
        data.put("personnelIds", new ArrayList<>(appliedIds));
        data.put("source", "ingest");
        publisher.publish(LiveEventTypes.PERSON_MOVED, data);
    }

    /** 把最新上报坐标覆盖到台账副本上；没有上报过则原样返回。 */
    public DemoPersonnel overlay(DemoPersonnel person) {
        if (person == null) {
            return null;
        }
        LivePosition live = positions.get(person.id);
        if (live == null) {
            return person;
        }
        DemoPersonnel copy = person.copy();
        copy.x = live.x();
        copy.y = live.y();
        copy.area = live.area();
        if (live.battery() != null) {
            copy.battery = live.battery();
        }
        if (live.quality() != null) {
            copy.positioningQuality = live.quality();
        }
        copy.coordinate = "UWB / " + trim(live.x()) + ", " + trim(live.y());
        copy.lastUpdated = "实时";
        return copy;
    }

    /** 当前已接入上报的人员数，供运维/测试观察链路是否真的在跑。 */
    public int trackedCount() {
        return positions.size();
    }

    private static String resolveArea(double x, double y) {
        for (AreaRect rect : AREA_RECTS) {
            if (rect.contains(x, y)) {
                return rect.area();
            }
        }
        return AREA_FALLBACK;
    }

    private static String qualityOf(Double accuracy) {
        if (accuracy == null || accuracy.isNaN() || accuracy <= 0) {
            return null;
        }
        if (accuracy <= QUALITY_EXCELLENT_M) {
            return "优秀";
        }
        if (accuracy <= QUALITY_GOOD_M) {
            return "良好";
        }
        return "较低";
    }

    private static double clampPercent(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 50;
        }
        return Math.max(0, Math.min(100, value));
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
