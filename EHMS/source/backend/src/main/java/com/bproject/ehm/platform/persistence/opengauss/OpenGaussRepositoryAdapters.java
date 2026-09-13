package com.bproject.ehm.platform.persistence.opengauss;

import com.bproject.ehm.alarm.application.AlarmMetrics;
import com.bproject.ehm.alarm.domain.model.Alarm;
import com.bproject.ehm.alarm.domain.model.AlarmStatus;
import com.bproject.ehm.alarm.ports.AlarmRepository;
import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.asset.domain.model.Component;
import com.bproject.ehm.asset.domain.model.MeasurementPoint;
import com.bproject.ehm.asset.ports.AssetRepository;
import com.bproject.ehm.asset.ports.ComponentRepository;
import com.bproject.ehm.asset.ports.MeasurementPointRepository;
import com.bproject.ehm.caseflow.domain.model.MaintenanceCase;
import com.bproject.ehm.caseflow.ports.MaintenanceCaseRepository;
import com.bproject.ehm.health.domain.model.HealthAssessment;
import com.bproject.ehm.health.ports.HealthAssessmentRepository;
import com.bproject.ehm.maintenance.application.MaintenanceMetrics;
import com.bproject.ehm.maintenance.domain.model.DefectRecord;
import com.bproject.ehm.maintenance.domain.model.InspectionTask;
import com.bproject.ehm.maintenance.domain.model.InspectionTaskStatus;
import com.bproject.ehm.maintenance.domain.model.MaintenancePlan;
import com.bproject.ehm.maintenance.domain.model.WorkOrder;
import com.bproject.ehm.maintenance.domain.model.WorkOrderStatus;
import com.bproject.ehm.maintenance.ports.DefectRepository;
import com.bproject.ehm.maintenance.ports.InspectionTaskRepository;
import com.bproject.ehm.maintenance.ports.MaintenancePlanRepository;
import com.bproject.ehm.maintenance.ports.WorkOrderRepository;
import com.bproject.ehm.monitoring.domain.model.DeviceSnapshot;
import com.bproject.ehm.monitoring.domain.model.PointQualitySnapshot;
import com.bproject.ehm.monitoring.domain.model.QualityStatus;
import com.bproject.ehm.monitoring.domain.model.SnapshotMetrics;
import com.bproject.ehm.monitoring.ports.DeviceSnapshotRepository;
import com.bproject.ehm.monitoring.ports.PointQualityRepository;
import com.bproject.ehm.platform.audit.domain.model.AuditRecord;
import com.bproject.ehm.platform.audit.ports.AuditRepository;
import com.bproject.ehm.reliability.domain.model.AlarmRule;
import com.bproject.ehm.reliability.domain.model.FailureMode;
import com.bproject.ehm.reliability.domain.model.KnowledgeCase;
import com.bproject.ehm.reliability.domain.model.SlaPolicy;
import com.bproject.ehm.reliability.ports.ReliabilityRepository;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import com.bproject.ehm.spares.domain.model.SparePart;
import com.bproject.ehm.spares.domain.model.SpareReservation;
import com.bproject.ehm.spares.domain.model.StockBalance;
import com.bproject.ehm.spares.domain.model.StockTransaction;
import com.bproject.ehm.spares.ports.SparePartRepository;
import com.bproject.ehm.spares.ports.SpareReservationRepository;
import com.bproject.ehm.spares.ports.StockBalanceRepository;
import com.bproject.ehm.spares.ports.StockTransactionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class OpenGaussRepositoryAdapters {
    private OpenGaussRepositoryAdapters() {
    }
}

abstract class OpenGaussAdapterSupport {
    protected final OpenGaussJsonStore store;

    protected OpenGaussAdapterSupport(OpenGaussJsonStore store) {
        this.store = store;
    }

    protected static boolean contains(String value, String keyword) {
        return keyword == null || keyword.isBlank()
                || (value != null && value.toLowerCase(Locale.ROOT).contains(keyword.trim().toLowerCase(Locale.ROOT)));
    }

    protected static <T> PageResult<T> page(List<T> values, PageQuery query) {
        long offset = (long) query.page() * query.size();
        int from = (int) Math.min(offset, values.size());
        int to = Math.min(from + query.size(), values.size());
        return PageResult.of(values.subList(from, to), query, values.size());
    }

    protected static <T extends Comparable<? super T>> Comparator<T> newestFirst() {
        return Comparator.nullsLast(Comparator.reverseOrder());
    }
}

@Repository
@Profile("server")
class OpenGaussAssetRepository extends OpenGaussAdapterSupport implements AssetRepository {
    private static final String TYPE = "asset";
    OpenGaussAssetRepository(OpenGaussJsonStore store) { super(store); }

    public PageResult<Asset> findActive(PageQuery query, String keyword, String area, String type) {
        List<Asset> values = store.findAll(TYPE, Asset.class).stream()
                .filter(value -> !value.archived())
                .filter(value -> contains(value.code(), keyword) || contains(value.name(), keyword))
                .filter(value -> area == null || area.isBlank() || area.trim().equals(value.area()))
                .filter(value -> type == null || type.isBlank() || type.trim().equals(value.type()))
                .sorted(Comparator.comparing(Asset::code)).toList();
        return page(values, query);
    }
    public Optional<Asset> findByCode(String code) { return store.find(TYPE, code, Asset.class); }
    public boolean existsByCode(String code) { return findByCode(code).isPresent(); }
    public Asset save(Asset value) { return store.save(TYPE, value.code(), value); }
    public long countActive() { return store.findAll(TYPE, Asset.class).stream().filter(value -> !value.archived()).count(); }
}

@Repository
@Profile("server")
class OpenGaussComponentRepository extends OpenGaussAdapterSupport implements ComponentRepository {
    private static final String TYPE = "component";
    OpenGaussComponentRepository(OpenGaussJsonStore store) { super(store); }

    public PageResult<Component> findActiveByAssetCode(String assetCode, PageQuery query, String keyword) {
        List<Component> values = store.findAll(TYPE, Component.class).stream()
                .filter(value -> assetCode.equals(value.assetCode()) && !value.archived())
                .filter(value -> contains(value.code(), keyword) || contains(value.name(), keyword)
                        || contains(value.category(), keyword))
                .sorted(Comparator.comparing(Component::parentCode,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Component::code)).toList();
        return page(values, query);
    }
    public Optional<Component> findByCode(String code) { return store.find(TYPE, code, Component.class); }
    public boolean existsByCode(String code) { return findByCode(code).isPresent(); }
    public Component save(Component value) { return store.save(TYPE, value.code(), value); }
    public long countActiveByAssetCode(String assetCode) {
        return store.findAll(TYPE, Component.class).stream()
                .filter(value -> assetCode.equals(value.assetCode()) && !value.archived()).count();
    }
}

@Repository
@Profile("server")
class OpenGaussMeasurementPointRepository extends OpenGaussAdapterSupport implements MeasurementPointRepository {
    private static final String TYPE = "measurement-point";
    OpenGaussMeasurementPointRepository(OpenGaussJsonStore store) { super(store); }

    public PageResult<MeasurementPoint> findActiveByAssetCode(String assetCode, PageQuery query, String keyword) {
        List<MeasurementPoint> values = active(assetCode).stream()
                .filter(value -> contains(value.code(), keyword) || contains(value.name(), keyword)
                        || contains(value.metric(), keyword))
                .sorted(Comparator.comparing(MeasurementPoint::componentCode)
                        .thenComparing(MeasurementPoint::code)).toList();
        return page(values, query);
    }
    public List<MeasurementPoint> findActiveByCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) return List.of();
        Set<String> selected = Set.copyOf(codes);
        return store.findAll(TYPE, MeasurementPoint.class).stream()
                .filter(value -> !value.archived() && selected.contains(value.code())).toList();
    }
    public List<String> findActiveCodesByAssetCode(String assetCode) {
        return active(assetCode).stream().map(MeasurementPoint::code).toList();
    }
    public List<String> findEnabledCodesByAssetCode(String assetCode) {
        return active(assetCode).stream().filter(MeasurementPoint::enabled).map(MeasurementPoint::code).toList();
    }
    public Optional<MeasurementPoint> findByCode(String code) { return store.find(TYPE, code, MeasurementPoint.class); }
    public boolean existsByCode(String code) { return findByCode(code).isPresent(); }
    public MeasurementPoint save(MeasurementPoint value) { return store.save(TYPE, value.code(), value); }
    public long countActiveByAssetCode(String assetCode) { return active(assetCode).size(); }
    public long countEnabledByAssetCode(String assetCode) {
        return active(assetCode).stream().filter(MeasurementPoint::enabled).count();
    }
    public long countActiveByComponentCode(String componentCode) {
        return store.findAll(TYPE, MeasurementPoint.class).stream()
                .filter(value -> componentCode.equals(value.componentCode()) && !value.archived()).count();
    }
    private List<MeasurementPoint> active(String assetCode) {
        return store.findAll(TYPE, MeasurementPoint.class).stream()
                .filter(value -> assetCode.equals(value.assetCode()) && !value.archived()).toList();
    }
}

@Repository
@Profile("server")
class OpenGaussAlarmRepository extends OpenGaussAdapterSupport implements AlarmRepository {
    private static final String TYPE = "alarm";
    OpenGaussAlarmRepository(OpenGaussJsonStore store) { super(store); }

    public PageResult<Alarm> findAll(PageQuery query) {
        return page(store.findAll(TYPE, Alarm.class).stream()
                .sorted(Comparator.comparing(Alarm::occurredAt, newestFirst())).toList(), query);
    }
    public Optional<Alarm> findByAlarmNo(String alarmNo) { return store.find(TYPE, alarmNo, Alarm.class); }
    public List<Alarm> findOpenByDeviceCode(String deviceCode, int limit) {
        return store.findAll(TYPE, Alarm.class).stream()
                .filter(value -> deviceCode.equals(value.deviceCode()) && isOpen(value))
                .sorted(Comparator.comparing(Alarm::occurredAt, newestFirst()))
                .limit(Math.max(0, limit)).toList();
    }
    public Alarm save(Alarm value) { return store.save(TYPE, value.alarmNo(), value); }
    public long countAll() { return store.count(TYPE); }
    public AlarmMetrics metrics() {
        List<Alarm> open = store.findAll(TYPE, Alarm.class).stream().filter(this::isOpen).toList();
        long critical = open.stream().filter(value -> "severe".equals(value.levelClass())
                || "critical".equals(value.levelClass())).count();
        return new AlarmMetrics(open.size(), critical);
    }
    private boolean isOpen(Alarm value) {
        return value.status() != AlarmStatus.CLOSED && value.status() != AlarmStatus.INVALID
                && value.status() != AlarmStatus.SUPPRESSED;
    }
}

@Repository
@Profile("server")
class OpenGaussMaintenanceCaseRepository extends OpenGaussAdapterSupport implements MaintenanceCaseRepository {
    private static final String TYPE = "maintenance-case";
    OpenGaussMaintenanceCaseRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<MaintenanceCase> findByAlarmNo(String alarmNo) {
        return store.find(TYPE, alarmNo, MaintenanceCase.class);
    }
    public Optional<MaintenanceCase> findByWorkOrderNo(String workOrderNo) {
        return store.findAll(TYPE, MaintenanceCase.class).stream()
                .filter(value -> workOrderNo.equals(value.workOrderNo())).findFirst();
    }
    public MaintenanceCase save(MaintenanceCase value) { return store.save(TYPE, value.alarmNo(), value); }
}

@Repository
@Profile("server")
class OpenGaussHealthAssessmentRepository extends OpenGaussAdapterSupport implements HealthAssessmentRepository {
    private static final String TYPE = "health-assessment";
    OpenGaussHealthAssessmentRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<HealthAssessment> findById(String id) { return store.find(TYPE, id, HealthAssessment.class); }
    public Optional<HealthAssessment> findLatestByAssetCode(String assetCode) {
        return recent(assetCode).stream().findFirst();
    }
    public List<HealthAssessment> findRecentByAssetCode(String assetCode, int limit) {
        return recent(assetCode).stream().limit(Math.max(1, Math.min(limit, 100))).toList();
    }
    public HealthAssessment save(HealthAssessment value) { return store.save(TYPE, value.assessmentId(), value); }
    private List<HealthAssessment> recent(String assetCode) {
        return store.findAll(TYPE, HealthAssessment.class).stream()
                .filter(value -> assetCode.equals(value.assetCode()))
                .sorted(Comparator.comparing(HealthAssessment::generatedAt, newestFirst())).toList();
    }
}

@Repository
@Profile("server")
class OpenGaussMaintenancePlanRepository extends OpenGaussAdapterSupport implements MaintenancePlanRepository {
    private static final String TYPE = "maintenance-plan";
    OpenGaussMaintenancePlanRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<MaintenancePlan> findById(String id) { return store.find(TYPE, id, MaintenancePlan.class); }
    public List<MaintenancePlan> findByAssetCode(String assetCode) {
        return store.findAll(TYPE, MaintenancePlan.class).stream()
                .filter(value -> assetCode.equals(value.assetCode()))
                .sorted(Comparator.comparing(MaintenancePlan::nextDueAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }
    public MaintenancePlan save(MaintenancePlan value) { return store.save(TYPE, value.planId(), value); }
}

@Repository
@Profile("server")
class OpenGaussInspectionTaskRepository extends OpenGaussAdapterSupport implements InspectionTaskRepository {
    private static final String TYPE = "inspection-task";
    OpenGaussInspectionTaskRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<InspectionTask> findByTaskNo(String taskNo) { return store.find(TYPE, taskNo, InspectionTask.class); }
    public List<InspectionTask> findByAssetCode(String assetCode) {
        return store.findAll(TYPE, InspectionTask.class).stream()
                .filter(value -> assetCode.equals(value.assetCode()))
                .sorted(Comparator.comparing(InspectionTask::scheduledAt, newestFirst())).toList();
    }
    public boolean hasOpenTaskForPlan(String planId) {
        return store.findAll(TYPE, InspectionTask.class).stream().anyMatch(value -> planId.equals(value.planId())
                && (value.status() == InspectionTaskStatus.PLANNED || value.status() == InspectionTaskStatus.IN_PROGRESS));
    }
    public InspectionTask save(InspectionTask value) { return store.save(TYPE, value.taskNo(), value); }
}

@Repository
@Profile("server")
class OpenGaussDefectRepository extends OpenGaussAdapterSupport implements DefectRepository {
    private static final String TYPE = "defect";
    OpenGaussDefectRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<DefectRecord> findByDefectNo(String defectNo) { return store.find(TYPE, defectNo, DefectRecord.class); }
    public List<DefectRecord> findByAssetCode(String assetCode) {
        return store.findAll(TYPE, DefectRecord.class).stream()
                .filter(value -> assetCode.equals(value.assetCode()))
                .sorted(Comparator.comparing(DefectRecord::discoveredAt, newestFirst())).toList();
    }
    public DefectRecord save(DefectRecord value) { return store.save(TYPE, value.defectNo(), value); }
}

@Repository
@Profile("server")
class OpenGaussWorkOrderRepository extends OpenGaussAdapterSupport implements WorkOrderRepository {
    private static final String TYPE = "work-order";
    OpenGaussWorkOrderRepository(OpenGaussJsonStore store) { super(store); }
    public PageResult<WorkOrder> findAll(PageQuery query) {
        return page(store.findAll(TYPE, WorkOrder.class).stream()
                .sorted(Comparator.comparing(WorkOrder::updatedAt, newestFirst())).toList(), query);
    }
    public Optional<WorkOrder> findByOrderNo(String orderNo) { return store.find(TYPE, orderNo, WorkOrder.class); }
    public WorkOrder save(WorkOrder value) { return store.save(TYPE, value.orderNo(), value); }
    public long countAll() { return store.count(TYPE); }
    public MaintenanceMetrics metrics() {
        List<WorkOrder> values = store.findAll(TYPE, WorkOrder.class);
        long active = values.stream().filter(value -> value.status() != WorkOrderStatus.CLOSED
                && value.status() != WorkOrderStatus.CANCELLED).count();
        long pending = values.stream().filter(value -> value.status() == WorkOrderStatus.SUBMITTED
                || value.status() == WorkOrderStatus.APPROVED).count();
        return new MaintenanceMetrics(values.size(), active, pending);
    }
}

@Repository
@Profile("server")
class OpenGaussDeviceSnapshotRepository extends OpenGaussAdapterSupport implements DeviceSnapshotRepository {
    private static final String TYPE = "device-snapshot";
    OpenGaussDeviceSnapshotRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<DeviceSnapshot> findByDeviceCode(String code) { return store.find(TYPE, code, DeviceSnapshot.class); }
    public List<DeviceSnapshot> findByDeviceCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) return List.of();
        Set<String> selected = Set.copyOf(codes);
        return store.findAll(TYPE, DeviceSnapshot.class).stream()
                .filter(value -> selected.contains(value.deviceCode())).toList();
    }
    public DeviceSnapshot save(DeviceSnapshot value) { return store.save(TYPE, value.deviceCode(), value); }
    public SnapshotMetrics metrics() {
        List<DeviceSnapshot> values = store.findAll(TYPE, DeviceSnapshot.class);
        long online = values.stream().filter(value -> !Set.of("离线", "停机", "已归档").contains(value.condition())).count();
        long assessable = values.stream().filter(value -> value.health() != null).count();
        long healthy = values.stream().filter(value -> value.health() != null && value.health() >= 80).count();
        long highRisk = values.stream().filter(value -> "severe".equals(value.riskClass())
                || "critical".equals(value.riskClass())).count();
        return new SnapshotMetrics(online, assessable, healthy, highRisk,
                averageHealth(values), averageQuality(values));
    }
    private double averageHealth(List<DeviceSnapshot> values) {
        return values.stream().filter(value -> value.health() != null)
                .mapToInt(DeviceSnapshot::health).average().orElse(0);
    }
    private double averageQuality(List<DeviceSnapshot> values) {
        return values.stream().filter(value -> value.quality() != null)
                .mapToDouble(DeviceSnapshot::quality).average().orElse(0);
    }
}

@Repository
@Profile("server")
class OpenGaussPointQualityRepository extends OpenGaussAdapterSupport implements PointQualityRepository {
    private static final String TYPE = "point-quality";
    OpenGaussPointQualityRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<PointQualitySnapshot> findByPointCode(String code) {
        return store.find(TYPE, code, PointQualitySnapshot.class);
    }
    public List<PointQualitySnapshot> findByPointCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) return List.of();
        Set<String> selected = Set.copyOf(codes);
        return store.findAll(TYPE, PointQualitySnapshot.class).stream()
                .filter(value -> selected.contains(value.pointCode())).toList();
    }
    public PointQualitySnapshot save(PointQualitySnapshot value) { return store.save(TYPE, value.pointCode(), value); }
    public Map<QualityStatus, Long> countByStatus(String assetCode, Collection<String> activeCodes) {
        Map<QualityStatus, Long> result = new EnumMap<>(QualityStatus.class);
        if (activeCodes == null || activeCodes.isEmpty()) return result;
        Set<String> selected = Set.copyOf(activeCodes);
        store.findAll(TYPE, PointQualitySnapshot.class).stream()
                .filter(value -> assetCode.equals(value.assetCode()) && selected.contains(value.pointCode()))
                .forEach(value -> result.merge(value.status(), 1L, Long::sum));
        return result;
    }
}

@Repository
@Profile("server")
class OpenGaussAuditRepository extends OpenGaussAdapterSupport implements AuditRepository {
    private static final String TYPE = "audit";
    OpenGaussAuditRepository(OpenGaussJsonStore store) { super(store); }
    public AuditRecord append(AuditRecord value) { return store.save(TYPE, value.auditId(), value); }
    public List<AuditRecord> latest(int limit) {
        return store.findAll(TYPE, AuditRecord.class).stream()
                .sorted(Comparator.comparing(AuditRecord::occurredAt, newestFirst()))
                .limit(Math.max(1, Math.min(limit, 200))).toList();
    }
}

@Repository
@Profile("server")
class OpenGaussReliabilityRepository extends OpenGaussAdapterSupport implements ReliabilityRepository {
    private static final String FAILURE = "failure-mode";
    private static final String RULE = "alarm-rule";
    private static final String CASE = "knowledge-case";
    private static final String SLA = "sla-policy";
    OpenGaussReliabilityRepository(OpenGaussJsonStore store) { super(store); }

    public List<FailureMode> failureModes() {
        return store.findAll(FAILURE, FailureMode.class).stream()
                .sorted(Comparator.comparingInt(FailureMode::rpn).reversed()).toList();
    }
    public Optional<FailureMode> failureMode(String id) { return store.find(FAILURE, id, FailureMode.class); }
    public FailureMode save(FailureMode value) { return store.save(FAILURE, value.failureModeId(), value); }
    public List<AlarmRule> alarmRules() {
        return store.findAll(RULE, AlarmRule.class).stream()
                .sorted(Comparator.comparing(AlarmRule::updatedAt, newestFirst())).toList();
    }
    public Optional<AlarmRule> alarmRule(String code) { return store.find(RULE, code, AlarmRule.class); }
    public AlarmRule save(AlarmRule value) { return store.save(RULE, value.ruleCode(), value); }
    public List<KnowledgeCase> knowledgeCases() {
        return store.findAll(CASE, KnowledgeCase.class).stream()
                .sorted(Comparator.comparing(KnowledgeCase::updatedAt, newestFirst())).toList();
    }
    public Optional<KnowledgeCase> knowledgeCase(String number) { return store.find(CASE, number, KnowledgeCase.class); }
    public KnowledgeCase save(KnowledgeCase value) { return store.save(CASE, value.caseNo(), value); }
    public List<SlaPolicy> slaPolicies() {
        return store.findAll(SLA, SlaPolicy.class).stream()
                .sorted(Comparator.comparing(SlaPolicy::severity).reversed()).toList();
    }
    public Optional<SlaPolicy> slaPolicy(String severity) { return store.find(SLA, severity, SlaPolicy.class); }
    public SlaPolicy save(SlaPolicy value) { return store.save(SLA, value.severity(), value); }
}

@Repository
@Profile("server")
class OpenGaussSparePartRepository extends OpenGaussAdapterSupport implements SparePartRepository {
    private static final String TYPE = "spare-part";
    OpenGaussSparePartRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<SparePart> findByPartCode(String code) {
        return store.find(TYPE, SparePart.normalize(code), SparePart.class);
    }
    public List<SparePart> findAllActive() {
        return store.findAll(TYPE, SparePart.class).stream().filter(SparePart::active)
                .sorted(Comparator.comparing(SparePart::partCode)).toList();
    }
    public SparePart save(SparePart value) { return store.save(TYPE, value.partCode(), value); }
}

@Repository
@Profile("server")
class OpenGaussSpareReservationRepository extends OpenGaussAdapterSupport implements SpareReservationRepository {
    private static final String TYPE = "spare-reservation";
    OpenGaussSpareReservationRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<SpareReservation> findByReservationNo(String number) {
        return store.find(TYPE, number, SpareReservation.class);
    }
    public List<SpareReservation> findRecent(String workOrderNo) {
        return store.findAll(TYPE, SpareReservation.class).stream()
                .filter(value -> workOrderNo == null || workOrderNo.isBlank()
                        || workOrderNo.trim().equals(value.workOrderNo()))
                .sorted(Comparator.comparing(SpareReservation::updatedAt, newestFirst()))
                .limit(100).toList();
    }
    public SpareReservation save(SpareReservation value) {
        return store.save(TYPE, value.reservationNo(), value);
    }
}

@Repository
@Profile("server")
class OpenGaussStockBalanceRepository extends OpenGaussAdapterSupport implements StockBalanceRepository {
    private static final String TYPE = "stock-balance";
    OpenGaussStockBalanceRepository(OpenGaussJsonStore store) { super(store); }
    public Optional<StockBalance> find(String warehouseCode, String partCode) {
        String warehouse = warehouseCode == null || warehouseCode.isBlank()
                ? "MAIN" : warehouseCode.trim().toUpperCase(Locale.ROOT);
        return store.find(TYPE, warehouse + ":" + SparePart.normalize(partCode), StockBalance.class);
    }
    public List<StockBalance> findAll() { return store.findAll(TYPE, StockBalance.class); }
    public StockBalance save(StockBalance value) { return store.save(TYPE, value.balanceId(), value); }
}

@Repository
@Profile("server")
class OpenGaussStockTransactionRepository extends OpenGaussAdapterSupport implements StockTransactionRepository {
    private static final String TYPE = "stock-transaction";
    OpenGaussStockTransactionRepository(OpenGaussJsonStore store) { super(store); }
    public List<StockTransaction> findRecent(String partCode, int limit) {
        String selected = partCode == null || partCode.isBlank() ? null : SparePart.normalize(partCode);
        return store.findAll(TYPE, StockTransaction.class).stream()
                .filter(value -> selected == null || selected.equals(value.partCode()))
                .sorted(Comparator.comparing(StockTransaction::occurredAt, newestFirst()))
                .limit(Math.max(1, Math.min(limit, 100))).toList();
    }
    public StockTransaction save(StockTransaction value) {
        return store.save(TYPE, value.transactionNo(), value);
    }
}
