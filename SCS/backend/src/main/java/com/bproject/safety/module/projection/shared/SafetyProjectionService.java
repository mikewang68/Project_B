package com.bproject.safety.module.projection.shared;

import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.projection.shared.ProjectionDtos.DeviceHealth;
import com.bproject.safety.module.projection.shared.ProjectionDtos.RiskCount;
import com.bproject.safety.module.projection.shared.ProjectionDtos.TrendPoint;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 安全态势共享聚合：大屏 / 安全态势首页统一从这里取数，禁止复制两套分布 / 计数 / Feed 逻辑。
 *
 * <p>所有告警口径实时来自 {@link AlertRepository}（Alert 唯一权威事件源）；
 * 在岗人数、设备台账、历史趋势曲线暂无后端来源，使用显式 demo 标记的 Demo 聚合。</p>
 */
@Service
public class SafetyProjectionService {

    // ---- 暂无真实后端来源的台账常量（Demo aggregation） ----
    public static final int DEMO_ON_DUTY = 128;
    public static final int DEMO_DEVICE_ONLINE = 36;
    public static final int DEMO_DEVICE_TOTAL = 38;
    public static final int DEMO_RISKY_DEVICES = 2;

    private final AlertRepository repository;
    /** 复查②：台账域仓储注入（在岗 / 设备健康 / 趋势全部库内聚合，替代硬编码常量）。 */
    private final com.bproject.safety.module.personnel.repository.PersonnelRepository personnelRepository;
    private final com.bproject.safety.module.collision.repository.CollisionRepository collisionRepository;
    private final com.bproject.safety.module.ops.repository.EdgeNodeRepository edgeNodeRepository;
    /** 复查②：摄像头台账聚合通道（@Nullable——无 DataSource 环境回退演示口径）。 */
    @org.springframework.lang.Nullable
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 兼容历史测试直连的单参便捷构造（无台账仓储 → 台账指标走演示口径）。 */
    public SafetyProjectionService(AlertRepository repository) {
        this(repository, null, null, null, null);
    }

    public SafetyProjectionService(AlertRepository repository,
                                   com.bproject.safety.module.personnel.repository.PersonnelRepository personnelRepository,
                                   com.bproject.safety.module.collision.repository.CollisionRepository collisionRepository,
                                   com.bproject.safety.module.ops.repository.EdgeNodeRepository edgeNodeRepository) {
        this(repository, personnelRepository, collisionRepository, edgeNodeRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SafetyProjectionService(AlertRepository repository,
                                   com.bproject.safety.module.personnel.repository.PersonnelRepository personnelRepository,
                                   com.bproject.safety.module.collision.repository.CollisionRepository collisionRepository,
                                   com.bproject.safety.module.ops.repository.EdgeNodeRepository edgeNodeRepository,
                                   @org.springframework.lang.Nullable org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.repository = repository;
        this.personnelRepository = personnelRepository;
        this.collisionRepository = collisionRepository;
        this.edgeNodeRepository = edgeNodeRepository;
        this.jdbc = jdbc;
    }

    public List<DemoAlert> allAlerts() {
        return repository.findAll();
    }

    /** 告警总量（D-2 指标用：count() 直读，不加载聚合列表）。 */
    public long allAlertCount() {
        return repository.count();
    }

    /** 未关闭的活动告警数（Step 8：存储侧 GROUP BY 计数，不再全量加载）。 */
    public long activeCount() {
        return repository.countByStatus().entrySet().stream()
                .filter(e -> !AlertStatuses.CLOSED.equals(e.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    /** 处置中口径：处理中 / 待复核 / 已升级。 */
    private static final java.util.Set<String> IN_HANDLING = java.util.Set.of(
            AlertStatuses.PROCESSING, AlertStatuses.PENDING_REVIEW, AlertStatuses.ESCALATED);

    public long processingCount() {
        return repository.countByStatus().entrySet().stream()
                .filter(e -> IN_HANDLING.contains(e.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    public long countByRiskOpen(String riskCodeOrLabel) {
        String riskCode = com.bproject.safety.module.alert.model.RiskLevels.normalize(riskCodeOrLabel);
        return repository.countOpenByRisk().getOrDefault(riskCode, 0L);
    }

    public long closedCount() {
        return repository.countByStatus().getOrDefault(AlertStatuses.CLOSED, 0L);
    }

    /** 风险类型分布（按数量倒序，存储侧 GROUP BY 计数）。 */
    public List<RiskCount> riskDistribution() {
        return repository.countByEventType().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new RiskCount(e.getKey(), e.getValue().intValue()))
                .toList();
    }

    /** 最近事件（按发生时间倒序，分页通道 SQL LIMIT 截断）。 */
    public List<DemoAlert> recent(int limit, int max) {
        int size = limit <= 0 ? 12 : Math.min(limit, max);
        return repository.page(new com.bproject.safety.module.alert.repository.AlertQuery(
                null, null, null, null, null, null, null, null, null, null, 1, size)).list();
    }

    /** 事件摘要（联动失败优先提示人工接管）。 */
    public String summaryOf(DemoAlert a) {
        if (a.linkageFailed) {
            return "PLC 回执超时，需人工接管";
        }
        return a.eventType == null ? a.title : a.eventType;
    }

    /** 设备健康台账（复查②：库内聚合——摄像头/防碰撞设备/边缘节点；无台账来源的类目不再虚构）。 */
    public List<DeviceHealth> deviceHealth() {
        if (personnelRepository == null || collisionRepository == null || edgeNodeRepository == null) {
            return fallbackDeviceHealth();
        }
        try {
            long camTotal = countDevice("SELECT count(*) FROM safety.device_camera WHERE is_deleted = false");
            long camOnline = countDevice(
                    "SELECT count(*) FROM safety.device_camera WHERE is_deleted = false AND health_code = 'NORMAL'");
            long colTotal = collisionRepository.findAll().stream()
                    .filter(d -> !d.isDeleted).count();
            long colOnline = collisionRepository.findAll().stream()
                    .filter(d -> "NORMAL".equals(d.healthCode)).count();
            long edgeTotal = edgeNodeRepository.count();
            long edgeOnline = edgeNodeRepository.findAll().stream()
                    .filter(n -> "ONLINE".equals(n.status)).count();
            if (camTotal == 0 && colTotal == 0 && edgeTotal == 0) {
                return fallbackDeviceHealth(); // 台账为空（Demo 灌种关闭且未建账）→ 显式演示口径
            }
            return List.of(
                    new DeviceHealth("摄像头", (int) camOnline, (int) camTotal, false),
                    new DeviceHealth("防碰撞设备", (int) colOnline, (int) colTotal, false),
                    new DeviceHealth("边缘节点", (int) edgeOnline, (int) edgeTotal, false));
        } catch (RuntimeException ex) {
            // 无库/无表环境（本地 dev 自动 H2 等）回退演示口径
            return fallbackDeviceHealth();
        }
    }

    private List<DeviceHealth> fallbackDeviceHealth() {
        return List.of(
                new DeviceHealth("摄像头", 22, 24, true),
                new DeviceHealth("雷达", 8, 8, true),
                new DeviceHealth("定位基站", 12, 12, true),
                new DeviceHealth("边缘节点", 4, 4, true));
    }

    private long countDevice(String sql) {
        org.springframework.jdbc.core.JdbcTemplate template = countJdbc();
        if (template == null) {
            return 0L;
        }
        java.util.List<Long> rows = template.query(sql,
                (rs, rowNum) -> rs.getLong(1));
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /** 复查②：设备表聚合的 JDBC 通道（无 DataSource 时返回 null → 调用方走回退）。 */
    private org.springframework.jdbc.core.JdbcTemplate countJdbc() {
        if (jdbc == null) {
            return null;
        }
        try {
            jdbc.queryForMap("SELECT 1");
            return jdbc;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 复查②：在岗人数（personnel 表 online_status_code=ONLINE 真实聚合；无台账回退演示常量）。 */
    public long onDutyCount() {
        if (personnelRepository == null) {
            return DEMO_ON_DUTY;
        }
        long online = personnelRepository.findAll().stream()
                .filter(p -> com.bproject.safety.module.personnel.model.PersonStatuses.ONLINE.equals(p.statusCode))
                .count();
        return online > 0 ? online : DEMO_ON_DUTY;
    }

    /** 在岗数是否为演示口径（0 在岗回退常量时为 true）。 */
    public boolean onDutyIsDemo() {
        if (personnelRepository == null) {
            return true;
        }
        return personnelRepository.findAll().stream()
                .noneMatch(p -> com.bproject.safety.module.personnel.model.PersonStatuses.ONLINE.equals(p.statusCode));
    }

    /** 近 24 小时趋势（复查②：告警库按小时桶真实聚合，空桶补齐；无数据时回退 Demo 曲线）。 */
    public List<TrendPoint> trend24h() {
        try {
            var buckets = repository.trendByHour(24);
            if (buckets.isEmpty()) {
                return fallbackTrend24h();
            }
            java.util.Map<String, long[]> byLabel = new java.util.LinkedHashMap<>();
            buckets.forEach(b -> byLabel.put(b.label(), new long[]{b.total(), b.high()}));
            java.util.List<TrendPoint> points = new java.util.ArrayList<>();
            java.time.ZonedDateTime base = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            for (int i = 23; i >= 0; i--) {
                String label = base.minusHours(i).format(java.time.format.DateTimeFormatter.ofPattern("HH")) + ":00";
                long[] arr = byLabel.getOrDefault(label, new long[]{0, 0});
                points.add(new TrendPoint(label, (int) arr[0], (int) arr[1], false));
            }
            return points;
        } catch (RuntimeException ex) {
            return fallbackTrend24h();
        }
    }

    private List<TrendPoint> fallbackTrend24h() {
        int[] totals = {2, 1, 1, 0, 1, 0, 1, 2, 3, 4, 3, 5, 4, 6, 5, 7, 6, 8, 7, 9, 6, 5, 4, 3};
        int[] high = {0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 2, 1, 2, 1, 2, 1, 3, 2, 1, 1, 1};
        java.util.List<TrendPoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < totals.length; i++) {
            points.add(new TrendPoint(String.format("%02d:00", i), totals[i], high[i], true));
        }
        return points;
    }

    /** 近 7 日趋势（复查②：告警库按日桶真实聚合，空桶补齐；无数据时回退 Demo 曲线）。 */
    public List<TrendPoint> trend7d() {
        try {
            var buckets = repository.trendByDay(7);
            if (buckets.isEmpty()) {
                return fallbackTrend7d();
            }
            java.util.Map<String, long[]> byLabel = new java.util.LinkedHashMap<>();
            buckets.forEach(b -> byLabel.put(b.label(), new long[]{b.total(), b.high()}));
            java.util.List<TrendPoint> points = new java.util.ArrayList<>();
            java.time.ZonedDateTime base = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("MM/dd");
            for (int i = 6; i >= 0; i--) {
                String label = base.minusDays(i).format(fmt);
                long[] arr = byLabel.getOrDefault(label, new long[]{0, 0});
                points.add(new TrendPoint(label, (int) arr[0], (int) arr[1], false));
            }
            return points;
        } catch (RuntimeException ex) {
            return fallbackTrend7d();
        }
    }

    private List<TrendPoint> fallbackTrend7d() {
        int[] totals = {9, 13, 11, 16, 14, 12, 17};
        int[] high = {2, 3, 2, 4, 3, 2, 4};
        String[] labels = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        java.util.List<TrendPoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < totals.length; i++) {
            points.add(new TrendPoint(labels[i], totals[i], high[i], true));
        }
        return points;
    }
}
