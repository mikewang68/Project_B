package com.bproject.ehm.spares.application;

import com.bproject.ehm.maintenance.application.MaintenanceMetrics;
import com.bproject.ehm.maintenance.domain.model.WorkOrder;
import com.bproject.ehm.maintenance.ports.WorkOrderRepository;
import com.bproject.ehm.shared.error.DomainConflictException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpareInventoryApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private final Map<String, SparePart> partStore = new LinkedHashMap<>();
    private final Map<String, StockBalance> balanceStore = new LinkedHashMap<>();
    private final Map<String, SpareReservation> reservationStore = new LinkedHashMap<>();
    private final List<StockTransaction> transactionStore = new ArrayList<>();
    private final Map<String, WorkOrder> orderStore = new LinkedHashMap<>();
    private SpareInventoryApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SpareInventoryApplicationService(parts(), balances(), reservations(), transactions(),
                workOrders(), Clock.fixed(NOW, ZoneOffset.UTC));
        service.createPart("BRK-01", "制动器摩擦片", "GT系列", "制动系统", "片",
                List.of("GT-01"), 2, 4, 15, BigDecimal.valueOf(680), "MAIN");
        orderStore.put("WO-001", WorkOrder.create("WO-001", "GT-01", "1#门式起重机", "制动器检修",
                "P1 高", "机修班", "点检缺陷", "更换摩擦片", "夜班", "工程师", NOW));
    }

    @Test
    void receiptReservationIssueAndReleaseProduceAuditableFlow() {
        service.receive("BRK-01", "MAIN", 8, "采购到货", "库管员");
        SpareOperationResult reserved = service.reserve("WO-001", "BRK-01", "MAIN", 5,
                "工单预计使用", "维修负责人");
        service.issue(reserved.reservation().reservationNo(), 3, "现场领用", "库管员");
        SpareOperationResult released = service.release(reserved.reservation().reservationNo(),
                "工单完工释放余量", "库管员");

        assertEquals(5, released.balance().onHandQuantity());
        assertEquals(0, released.balance().reservedQuantity());
        assertEquals("RELEASED", released.reservation().status());
        assertEquals(List.of("RECEIPT", "RESERVE", "ISSUE", "RELEASE"),
                transactionStore.stream().map(StockTransaction::transactionType).toList());
    }

    @Test
    void overReservationIsRejectedWithoutReservationRecord() {
        service.receive("BRK-01", "MAIN", 2, "到货", "库管员");
        assertThrows(DomainConflictException.class,
                () -> service.reserve("WO-001", "BRK-01", "MAIN", 3, "维修", "负责人"));
        assertTrue(reservationStore.isEmpty());
    }

    @Test
    void lowStockSuggestionContainsQuantityAndAmount() {
        service.receive("BRK-01", "MAIN", 1, "期初库存", "库管员");
        SparePartView alert = service.lowStockAlerts("MAIN").get(0);
        assertEquals(5, alert.suggestedPurchaseQuantity());
        assertEquals(0, BigDecimal.valueOf(3400).compareTo(alert.suggestedPurchaseAmount()));
    }

    private SparePartRepository parts() {
        return new SparePartRepository() {
            public Optional<SparePart> findByPartCode(String code) { return Optional.ofNullable(partStore.get(code)); }
            public List<SparePart> findAllActive() { return partStore.values().stream().filter(SparePart::active).toList(); }
            public SparePart save(SparePart value) { partStore.put(value.partCode(), value); return value; }
        };
    }

    private StockBalanceRepository balances() {
        return new StockBalanceRepository() {
            public Optional<StockBalance> find(String warehouse, String part) { return Optional.ofNullable(balanceStore.get(warehouse + ":" + part)); }
            public List<StockBalance> findAll() { return List.copyOf(balanceStore.values()); }
            public StockBalance save(StockBalance value) { balanceStore.put(value.balanceId(), value); return value; }
        };
    }

    private SpareReservationRepository reservations() {
        return new SpareReservationRepository() {
            public Optional<SpareReservation> findByReservationNo(String no) { return Optional.ofNullable(reservationStore.get(no)); }
            public List<SpareReservation> findRecent(String orderNo) { return reservationStore.values().stream().filter(value -> orderNo == null || orderNo.equals(value.workOrderNo())).toList(); }
            public SpareReservation save(SpareReservation value) { reservationStore.put(value.reservationNo(), value); return value; }
        };
    }

    private StockTransactionRepository transactions() {
        return new StockTransactionRepository() {
            public List<StockTransaction> findRecent(String partCode, int limit) { return transactionStore.stream().limit(limit).toList(); }
            public StockTransaction save(StockTransaction value) { transactionStore.add(value); return value; }
        };
    }

    private WorkOrderRepository workOrders() {
        return new WorkOrderRepository() {
            public PageResult<WorkOrder> findAll(PageQuery page) { return null; }
            public Optional<WorkOrder> findByOrderNo(String orderNo) { return Optional.ofNullable(orderStore.get(orderNo)); }
            public WorkOrder save(WorkOrder workOrder) { orderStore.put(workOrder.orderNo(), workOrder); return workOrder; }
            public long countAll() { return orderStore.size(); }
            public MaintenanceMetrics metrics() { return new MaintenanceMetrics(orderStore.size(), orderStore.size(), orderStore.size()); }
        };
    }
}
