package com.bproject.ehm.spares.application;

import com.bproject.ehm.maintenance.domain.model.WorkOrder;
import com.bproject.ehm.maintenance.domain.model.WorkOrderStatus;
import com.bproject.ehm.maintenance.ports.WorkOrderRepository;
import com.bproject.ehm.shared.error.DomainConflictException;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import com.bproject.ehm.shared.error.ValidationException;
import com.bproject.ehm.spares.domain.model.SparePart;
import com.bproject.ehm.spares.domain.model.SpareReservation;
import com.bproject.ehm.spares.domain.model.StockBalance;
import com.bproject.ehm.spares.domain.model.StockTransaction;
import com.bproject.ehm.spares.ports.SparePartRepository;
import com.bproject.ehm.spares.ports.SpareReservationRepository;
import com.bproject.ehm.spares.ports.StockBalanceRepository;
import com.bproject.ehm.spares.ports.StockTransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class SpareInventoryApplicationService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final Set<WorkOrderStatus> TERMINAL_ORDERS = Set.of(
            WorkOrderStatus.CLOSED, WorkOrderStatus.CANCELLED, WorkOrderStatus.REJECTED);

    private final SparePartRepository parts;
    private final StockBalanceRepository balances;
    private final SpareReservationRepository reservations;
    private final StockTransactionRepository transactions;
    private final WorkOrderRepository workOrders;
    private final Clock clock;

    @Autowired
    public SpareInventoryApplicationService(SparePartRepository parts,
                                            StockBalanceRepository balances,
                                            SpareReservationRepository reservations,
                                            StockTransactionRepository transactions,
                                            WorkOrderRepository workOrders) {
        this(parts, balances, reservations, transactions, workOrders, Clock.systemUTC());
    }

    SpareInventoryApplicationService(SparePartRepository parts,
                                     StockBalanceRepository balances,
                                     SpareReservationRepository reservations,
                                     StockTransactionRepository transactions,
                                     WorkOrderRepository workOrders,
                                     Clock clock) {
        this.parts = parts;
        this.balances = balances;
        this.reservations = reservations;
        this.transactions = transactions;
        this.workOrders = workOrders;
        this.clock = clock;
    }

    public List<SparePartView> listParts(String warehouseCode) {
        String warehouse = normalizeWarehouse(warehouseCode);
        Instant now = clock.instant();
        return parts.findAllActive().stream()
                .map(part -> SparePartView.from(part, balance(warehouse, part.partCode(), now)))
                .toList();
    }

    public List<SparePartView> lowStockAlerts(String warehouseCode) {
        return listParts(warehouseCode).stream().filter(SparePartView::lowStock).toList();
    }

    public SpareInventorySummary summary(String warehouseCode) {
        List<SparePartView> views = listParts(warehouseCode);
        BigDecimal value = views.stream()
                .map(item -> item.unitCost().multiply(BigDecimal.valueOf(item.onHandQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal suggested = views.stream().map(SparePartView::suggestedPurchaseAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long active = reservations.findRecent(null).stream()
                .filter(item -> !"ISSUED".equals(item.status()) && !"RELEASED".equals(item.status())).count();
        return new SpareInventorySummary(views.size(), (int) views.stream().filter(SparePartView::lowStock).count(),
                (int) views.stream().filter(item -> item.reservedQuantity() > 0).count(),
                value, suggested, active);
    }

    public SparePartView createPart(String partCode, String name, String specification, String category,
                                    String unit, List<String> compatibleAssets, double safetyStock,
                                    double reorderPoint, int leadTimeDays, BigDecimal unitCost,
                                    String warehouseCode) {
        try {
            String code = SparePart.normalize(partCode);
            if (parts.findByPartCode(code).isPresent()) throw new DomainConflictException("备件编码已存在：" + code);
            Instant now = clock.instant();
            SparePart part = parts.save(SparePart.create(code, name, specification, category, unit,
                    compatibleAssets, safetyStock, reorderPoint, leadTimeDays, unitCost, now));
            StockBalance stock = balances.save(StockBalance.empty(normalizeWarehouse(warehouseCode), code, now));
            return SparePartView.from(part, stock);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public SpareOperationResult receive(String partCode, String warehouseCode, double quantity,
                                        String reason, String operator) {
        try {
            SparePart part = requirePart(partCode);
            Instant now = clock.instant();
            StockBalance saved = balances.save(balance(warehouseCode, part.partCode(), now).receive(quantity, now));
            StockTransaction transaction = saveTransaction("RECEIPT", saved, null, null, quantity,
                    fallback(reason, "采购到货入库"), operator, now);
            return new SpareOperationResult(saved, null, transaction);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public SpareOperationResult reserve(String workOrderNo, String partCode, String warehouseCode,
                                        double quantity, String purpose, String requester) {
        try {
            WorkOrder order = requireActiveWorkOrder(workOrderNo);
            SparePart part = requirePart(partCode);
            validateCompatibility(part, order.deviceCode());
            Instant now = clock.instant();
            StockBalance savedBalance = balances.save(balance(warehouseCode, part.partCode(), now).reserve(quantity, now));
            SpareReservation reservation = SpareReservation.create(
                    "RSV-" + DAY.format(now) + "-" + shortId(), order.orderNo(), order.deviceCode(),
                    savedBalance.warehouseCode(), part, quantity, purpose, requester, now);
            SpareReservation savedReservation = reservations.save(reservation);
            StockTransaction transaction = saveTransaction("RESERVE", savedBalance, order.orderNo(),
                    savedReservation.reservationNo(), quantity, savedReservation.purpose(), requester, now);
            return new SpareOperationResult(savedBalance, savedReservation, transaction);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public SpareOperationResult issue(String reservationNo, double quantity, String reason, String operator) {
        try {
            SpareReservation current = requireReservation(reservationNo);
            requireActiveWorkOrder(current.workOrderNo());
            Instant now = clock.instant();
            SpareReservation changed = current.issue(quantity, now);
            StockBalance savedBalance = balances.save(requireBalance(current).issue(quantity, now));
            SpareReservation savedReservation = reservations.save(changed);
            StockTransaction transaction = saveTransaction("ISSUE", savedBalance, current.workOrderNo(),
                    current.reservationNo(), quantity, fallback(reason, "按工单领用"), operator, now);
            return new SpareOperationResult(savedBalance, savedReservation, transaction);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public SpareOperationResult release(String reservationNo, String reason, String operator) {
        SpareReservation current = requireReservation(reservationNo);
        double quantity = current.remainingQuantity();
        Instant now = clock.instant();
        SpareReservation changed = current.release(reason, now);
        StockBalance savedBalance = balances.save(requireBalance(current).release(quantity, now));
        SpareReservation savedReservation = reservations.save(changed);
        StockTransaction transaction = saveTransaction("RELEASE", savedBalance, current.workOrderNo(),
                current.reservationNo(), quantity, fallback(reason, "释放未领用预留"), operator, now);
        return new SpareOperationResult(savedBalance, savedReservation, transaction);
    }

    public SpareOperationResult returnToStock(String partCode, String warehouseCode, double quantity,
                                              String workOrderNo, String reason, String operator) {
        try {
            SparePart part = requirePart(partCode);
            if (workOrderNo != null && !workOrderNo.isBlank()) requireWorkOrder(workOrderNo);
            Instant now = clock.instant();
            StockBalance saved = balances.save(balance(warehouseCode, part.partCode(), now).returnToStock(quantity, now));
            StockTransaction transaction = saveTransaction("RETURN", saved, blankToNull(workOrderNo), null,
                    quantity, fallback(reason, "工单余料退库"), operator, now);
            return new SpareOperationResult(saved, null, transaction);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public List<SpareReservation> listReservations(String workOrderNo) {
        return reservations.findRecent(blankToNull(workOrderNo));
    }

    public List<StockTransaction> listTransactions(String partCode, int limit) {
        return transactions.findRecent(blankToNull(partCode), limit);
    }

    private SparePart requirePart(String partCode) {
        String code = SparePart.normalize(partCode);
        return parts.findByPartCode(code)
                .filter(SparePart::active)
                .orElseThrow(() -> new ResourceNotFoundException("未找到有效备件：" + code));
    }

    private WorkOrder requireWorkOrder(String workOrderNo) {
        if (workOrderNo == null || workOrderNo.isBlank()) throw new ValidationException("工单编号不能为空");
        return workOrders.findByOrderNo(workOrderNo.trim())
                .orElseThrow(() -> new ResourceNotFoundException("未找到工单：" + workOrderNo));
    }

    private WorkOrder requireActiveWorkOrder(String workOrderNo) {
        WorkOrder order = requireWorkOrder(workOrderNo);
        if (TERMINAL_ORDERS.contains(order.status())) {
            throw new DomainConflictException("工单状态为“" + order.status().label() + "”，不能预留或领用备件");
        }
        return order;
    }

    private SpareReservation requireReservation(String reservationNo) {
        if (reservationNo == null || reservationNo.isBlank()) throw new ValidationException("预留编号不能为空");
        return reservations.findByReservationNo(reservationNo.trim())
                .orElseThrow(() -> new ResourceNotFoundException("未找到备件预留：" + reservationNo));
    }

    private StockBalance requireBalance(SpareReservation reservation) {
        return balances.find(reservation.warehouseCode(), reservation.partCode())
                .orElseThrow(() -> new DomainConflictException("预留关联的库存余额不存在，请联系管理员核对库存流水"));
    }

    private StockBalance balance(String warehouseCode, String partCode, Instant now) {
        String warehouse = normalizeWarehouse(warehouseCode);
        return balances.find(warehouse, partCode).orElseGet(() -> StockBalance.empty(warehouse, partCode, now));
    }

    private void validateCompatibility(SparePart part, String assetCode) {
        if (!part.compatibleAssets().isEmpty() && part.compatibleAssets().stream().noneMatch(assetCode::equalsIgnoreCase)) {
            throw new DomainConflictException("备件" + part.partCode() + "未配置为设备" + assetCode + "的适用备件");
        }
    }

    private StockTransaction saveTransaction(String type, StockBalance balance, String orderNo,
                                             String reservationNo, double quantity, String reason,
                                             String operator, Instant now) {
        return transactions.save(new StockTransaction("STX-" + DAY.format(now) + "-" + shortId(), type,
                balance.warehouseCode(), balance.partCode(), orderNo, reservationNo, round(quantity),
                balance.onHandQuantity(), balance.reservedQuantity(), reason,
                fallback(operator, "Demo库管员"), now));
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String normalizeWarehouse(String value) {
        return value == null || value.isBlank() ? "MAIN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
