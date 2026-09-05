package com.mt.wms.inventory;

import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.TenantContextService;
import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.masterdata.BusinessSequenceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Service
class InventoryService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final Set<String> QUALITY_TYPES = Set.of("ZP", "CC", "DJ", "ZT", "JS", "XS");
    private static final Set<String> SERIAL_SOURCES = Set.of("SYSTEM", "ERP", "IMPORT", "USER");

    private final InventoryRepository repository;
    private final TenantContextService tenants;
    private final BusinessSequenceService sequences;

    InventoryService(InventoryRepository repository, TenantContextService tenants, BusinessSequenceService sequences) {
        this.repository = repository;
        this.tenants = tenants;
        this.sequences = sequences;
    }

    List<InventoryModels.BalanceView> balances(WmsPrincipal principal, HttpSession session) {
        Scope scope = scope(principal, session);
        return repository.balances(scope.companyId(), scope.warehouseId(), scope.ownerId());
    }

    List<InventoryModels.TransactionView> transactions(WmsPrincipal principal, HttpSession session) {
        Scope scope = scope(principal, session);
        return repository.transactions(scope.companyId(), scope.warehouseId(), scope.ownerId());
    }

    List<InventoryModels.WarningView> warnings(WmsPrincipal principal, HttpSession session) {
        Scope scope = scope(principal, session);
        return repository.warnings(scope.companyId(), scope.warehouseId(), scope.ownerId());
    }

    List<InventoryModels.SerialView> serials(WmsPrincipal principal, HttpSession session) {
        Scope scope = scope(principal, session);
        return repository.serials(scope.companyId(), scope.warehouseId(), scope.ownerId());
    }

    List<InventoryModels.ReplenishmentRuleView> replenishmentRules(WmsPrincipal principal, HttpSession session) {
        Scope scope = scope(principal, session);
        return repository.replenishmentRules(scope.companyId(), scope.warehouseId(), scope.ownerId());
    }

    @Transactional
    InventoryModels.ReplenishmentRuleView saveReplenishmentRule(Long id, InventoryModels.ReplenishmentRuleRequest request, WmsPrincipal principal, HttpSession session) {
        Scope scope = scope(principal, session);
        if (request.maxQty().compareTo(request.minQty()) < 0) throw new IllegalArgumentException("补齐数量不能小于告警数量");
        long goodId = repository.goodId(scope.companyId(), scope.ownerId(), request.goodCode().trim());
        long locationId = repository.locationId(scope.companyId(), scope.warehouseId(), request.locationCode().trim());
        String status = text(request.status()).toUpperCase();
        if (status.isEmpty()) status = "ENABLED";
        if (!Set.of("ENABLED", "DISABLED").contains(status)) throw new IllegalArgumentException("补货规则状态不合法");
        long savedId = repository.saveReplenishmentRule(id, scope.companyId(), scope.warehouseId(), scope.ownerId(), goodId, locationId, request.minQty(), request.maxQty(), status, request.remark());
        return repository.replenishmentRuleView(savedId, scope.companyId(), scope.warehouseId(), scope.ownerId());
    }

    @Transactional
    InventoryModels.ReplenishmentResult generateReplenishment(InventoryModels.ReplenishmentGenerateRequest request, WmsPrincipal principal, HttpSession session) {
        Scope scope = scope(principal, session);
        if (repository.operationProcessed(scope.companyId(), request.idempotencyKey())) throw new IllegalArgumentException("该补货请求已处理，请勿重复提交");
        String operationCode = null;
        long operationId = 0;
        int ruleCount = 0;
        int lineCount = 0;
        BigDecimal movedTotal = ZERO;
        for (InventoryRepository.RuleRow rule : repository.activeRulesForUpdate(scope.companyId(), scope.warehouseId(), scope.ownerId())) {
            BigDecimal current = repository.quantityAt(scope.warehouseId(), scope.ownerId(), rule.locationId(), rule.goodId());
            if (current.compareTo(rule.minQty()) >= 0) continue;
            BigDecimal remaining = rule.maxQty().subtract(current);
            boolean ruleMoved = false;
            for (InventoryRepository.BalanceRow source : repository.replenishSources(scope.companyId(), scope.warehouseId(), scope.ownerId(), rule.goodId(), rule.locationId())) {
                if (remaining.signum() <= 0) break;
                BigDecimal quantity = source.availableQty().min(remaining);
                if (quantity.signum() <= 0) continue;
                if (operationCode == null) {
                    operationCode = sequences.next("RP", principal, session);
                    operationId = repository.operation(scope.companyId(), scope.warehouseId(), scope.ownerId(), operationCode, "MOVE", principal.userId(), "库位自动补货", request.idempotencyKey());
                }
                InventoryRepository.BalanceRow destination = repository.lockDimension(scope.companyId(), scope.warehouseId(), scope.ownerId(), rule.locationId(), source.goodId(), source.batchCode(), source.qualityType(), source.supplierCode(), source.lpn())
                        .orElseGet(() -> {
                            long id = repository.createBalance(scope.companyId(), scope.warehouseId(), scope.ownerId(), rule.locationId(), source.goodId(), source.batchCode(), source.qualityType(), source.supplierCode(), source.productDate(), source.expireDate(), source.lpn());
                            return repository.lock(id, scope.companyId(), scope.warehouseId(), scope.ownerId()).orElseThrow();
                        });
                BigDecimal sourceAfter = source.availableQty().subtract(quantity);
                BigDecimal destinationAfter = destination.availableQty().add(quantity);
                repository.updateQuantities(source.id(), sourceAfter, source.allocatedQty(), source.frozenQty());
                repository.updateQuantities(destination.id(), destinationAfter, destination.allocatedQty(), destination.frozenQty());
                repository.line(operationId, source.goodId(), source.locationId(), rule.locationId(), source.id(), source.total(), quantity, quantity, ZERO, source.batchCode(), source.qualityType(), "库位自动补货");
                String transactionKey = request.idempotencyKey() + ":R" + rule.id() + ":S" + source.id();
                repository.transaction(scope.companyId(), scope.warehouseId(), scope.ownerId(), source.id(), source.goodId(), source.locationId(), "MOVE_OUT", operationCode,
                        source.total(), quantity.negate(), source.total().subtract(quantity), source.availableQty(), quantity.negate(), sourceAfter,
                        source.frozenQty(), ZERO, source.frozenQty(), rule.locationId(), transactionKey + ":OUT", principal.userId(), "库位自动补货");
                repository.transaction(scope.companyId(), scope.warehouseId(), scope.ownerId(), destination.id(), destination.goodId(), destination.locationId(), "MOVE_IN", operationCode,
                        destination.total(), quantity, destination.total().add(quantity), destination.availableQty(), quantity, destinationAfter,
                        destination.frozenQty(), ZERO, destination.frozenQty(), source.locationId(), transactionKey + ":IN", principal.userId(), "库位自动补货");
                remaining = remaining.subtract(quantity);
                movedTotal = movedTotal.add(quantity);
                lineCount++;
                ruleMoved = true;
            }
            if (ruleMoved) ruleCount++;
        }
        if (operationCode == null) throw new IllegalArgumentException("当前不需要补货，或没有可用的来源库存");
        return new InventoryModels.ReplenishmentResult(operationCode, ruleCount, lineCount, movedTotal);
    }

    @Transactional
    InventoryModels.OperationResult adjust(InventoryModels.AdjustmentRequest request, WmsPrincipal principal, HttpSession session) {
        Scope scope = scope(principal, session);
        rejectDuplicate(scope.companyId(), request.idempotencyKey());
        long goodId = repository.goodId(scope.companyId(), scope.ownerId(), request.goodCode().trim());
        long locationId = repository.locationId(scope.companyId(), scope.warehouseId(), request.locationCode().trim());
        String batch = dimension(request.batchCode());
        String quality = quality(request.qualityType());
        String supplier = dimension(request.supplierCode());
        String lpn = dimension(request.lpn());
        InventoryRepository.BalanceRow balance = repository.lockDimension(scope.companyId(), scope.warehouseId(), scope.ownerId(), locationId, goodId, batch, quality, supplier, lpn)
                .orElseGet(() -> {
                    long id = repository.createBalance(scope.companyId(), scope.warehouseId(), scope.ownerId(), locationId, goodId, batch, quality, supplier, request.productDate(), request.expireDate(), lpn);
                    return repository.lock(id, scope.companyId(), scope.warehouseId(), scope.ownerId()).orElseThrow();
                });
        BigDecimal before = balance.total();
        BigDecimal delta = request.quantityAfter().subtract(before);
        BigDecimal availableAfter = balance.availableQty().add(delta);
        requireNonNegative(availableAfter, "调整后数量不能小于已分配量与冻结量之和");
        String operationCode = sequences.next("IA", principal, session);
        long operationId = repository.operation(scope.companyId(), scope.warehouseId(), scope.ownerId(), operationCode, "ADJUST", principal.userId(), request.remark());
        repository.updateQuantities(balance.id(), availableAfter, balance.allocatedQty(), balance.frozenQty());
        repository.line(operationId, goodId, locationId, null, balance.id(), before, request.quantityAfter(), request.quantityAfter(), delta, batch, quality, request.remark());
        repository.transaction(scope.companyId(), scope.warehouseId(), scope.ownerId(), balance.id(), goodId, locationId, "ADJUST", operationCode,
                before, delta, request.quantityAfter(), balance.availableQty(), delta, availableAfter,
                balance.frozenQty(), ZERO, balance.frozenQty(), null, request.idempotencyKey(), principal.userId(), request.remark());
        return new InventoryModels.OperationResult(operationCode, repository.view(balance.id(), scope.companyId(), scope.warehouseId(), scope.ownerId()));
    }

    @Transactional
    InventoryModels.OperationResult freeze(InventoryModels.QuantityRequest request, WmsPrincipal principal, HttpSession session) {
        return transferAvailability(request, principal, session, true);
    }

    @Transactional
    InventoryModels.OperationResult unfreeze(InventoryModels.QuantityRequest request, WmsPrincipal principal, HttpSession session) {
        return transferAvailability(request, principal, session, false);
    }

    private InventoryModels.OperationResult transferAvailability(InventoryModels.QuantityRequest request, WmsPrincipal principal, HttpSession session, boolean freeze) {
        Scope scope = scope(principal, session);
        rejectDuplicate(scope.companyId(), request.idempotencyKey());
        InventoryRepository.BalanceRow balance = lock(request.balanceId(), scope);
        BigDecimal quantity = request.quantity();
        if (freeze && balance.availableQty().compareTo(quantity) < 0) throw new IllegalArgumentException("可用库存不足");
        if (!freeze && balance.frozenQty().compareTo(quantity) < 0) throw new IllegalArgumentException("冻结库存不足");
        BigDecimal availableAfter = freeze ? balance.availableQty().subtract(quantity) : balance.availableQty().add(quantity);
        BigDecimal frozenAfter = freeze ? balance.frozenQty().add(quantity) : balance.frozenQty().subtract(quantity);
        String type = freeze ? "FREEZE" : "UNFREEZE";
        String operationCode = sequences.next(freeze ? "IF" : "IU", principal, session);
        long operationId = repository.operation(scope.companyId(), scope.warehouseId(), scope.ownerId(), operationCode, type, principal.userId(), request.remark());
        repository.updateQuantities(balance.id(), availableAfter, balance.allocatedQty(), frozenAfter);
        repository.line(operationId, balance.goodId(), balance.locationId(), null, balance.id(), balance.total(), quantity, quantity, ZERO, balance.batchCode(), balance.qualityType(), request.remark());
        BigDecimal signed = freeze ? quantity.negate() : quantity;
        repository.transaction(scope.companyId(), scope.warehouseId(), scope.ownerId(), balance.id(), balance.goodId(), balance.locationId(), type, operationCode,
                balance.total(), ZERO, balance.total(), balance.availableQty(), signed, availableAfter,
                balance.frozenQty(), signed.negate(), frozenAfter, null, request.idempotencyKey(), principal.userId(), request.remark());
        return new InventoryModels.OperationResult(operationCode, repository.view(balance.id(), scope.companyId(), scope.warehouseId(), scope.ownerId()));
    }

    @Transactional
    InventoryModels.MoveResult move(InventoryModels.MoveRequest request, WmsPrincipal principal, HttpSession session) {
        Scope scope = scope(principal, session);
        rejectDuplicate(scope.companyId(), request.idempotencyKey());
        InventoryRepository.BalanceRow source = lock(request.balanceId(), scope);
        if (source.availableQty().compareTo(request.quantity()) < 0) throw new IllegalArgumentException("可用库存不足");
        long destinationLocationId = repository.locationId(scope.companyId(), scope.warehouseId(), request.destinationLocationCode().trim());
        if (destinationLocationId == source.locationId()) throw new IllegalArgumentException("目标库位不能与原库位相同");
        InventoryRepository.BalanceRow destination = repository.lockDimension(scope.companyId(), scope.warehouseId(), scope.ownerId(), destinationLocationId, source.goodId(), source.batchCode(), source.qualityType(), source.supplierCode(), source.lpn())
                .orElseGet(() -> {
                    long id = repository.createBalance(scope.companyId(), scope.warehouseId(), scope.ownerId(), destinationLocationId, source.goodId(), source.batchCode(), source.qualityType(), source.supplierCode(), source.productDate(), source.expireDate(), source.lpn());
                    return repository.lock(id, scope.companyId(), scope.warehouseId(), scope.ownerId()).orElseThrow();
                });
        String operationCode = sequences.next("IM", principal, session);
        long operationId = repository.operation(scope.companyId(), scope.warehouseId(), scope.ownerId(), operationCode, "MOVE", principal.userId(), request.remark());
        BigDecimal sourceAvailableAfter = source.availableQty().subtract(request.quantity());
        BigDecimal destinationAvailableAfter = destination.availableQty().add(request.quantity());
        repository.updateQuantities(source.id(), sourceAvailableAfter, source.allocatedQty(), source.frozenQty());
        repository.updateQuantities(destination.id(), destinationAvailableAfter, destination.allocatedQty(), destination.frozenQty());
        repository.line(operationId, source.goodId(), source.locationId(), destinationLocationId, source.id(), source.total(), request.quantity(), request.quantity(), ZERO, source.batchCode(), source.qualityType(), request.remark());
        repository.transaction(scope.companyId(), scope.warehouseId(), scope.ownerId(), source.id(), source.goodId(), source.locationId(), "MOVE_OUT", operationCode,
                source.total(), request.quantity().negate(), source.total().subtract(request.quantity()), source.availableQty(), request.quantity().negate(), sourceAvailableAfter,
                source.frozenQty(), ZERO, source.frozenQty(), destinationLocationId, request.idempotencyKey() + ":OUT", principal.userId(), request.remark());
        repository.transaction(scope.companyId(), scope.warehouseId(), scope.ownerId(), destination.id(), destination.goodId(), destination.locationId(), "MOVE_IN", operationCode,
                destination.total(), request.quantity(), destination.total().add(request.quantity()), destination.availableQty(), request.quantity(), destinationAvailableAfter,
                destination.frozenQty(), ZERO, destination.frozenQty(), source.locationId(), request.idempotencyKey() + ":IN", principal.userId(), request.remark());
        return new InventoryModels.MoveResult(operationCode,
                repository.view(source.id(), scope.companyId(), scope.warehouseId(), scope.ownerId()),
                repository.view(destination.id(), scope.companyId(), scope.warehouseId(), scope.ownerId()));
    }

    @Transactional
    InventoryModels.OperationResult count(InventoryModels.CountRequest request, WmsPrincipal principal, HttpSession session) {
        Scope scope = scope(principal, session);
        rejectDuplicate(scope.companyId(), request.idempotencyKey());
        InventoryRepository.BalanceRow balance = lock(request.balanceId(), scope);
        BigDecimal before = balance.total();
        BigDecimal delta = request.actualQuantity().subtract(before);
        BigDecimal availableAfter = balance.availableQty().add(delta);
        requireNonNegative(availableAfter, "实盘数量不能小于已分配量与冻结量之和");
        String operationCode = sequences.next("IC", principal, session);
        long operationId = repository.operation(scope.companyId(), scope.warehouseId(), scope.ownerId(), operationCode, "COUNT", principal.userId(), request.remark());
        repository.updateQuantities(balance.id(), availableAfter, balance.allocatedQty(), balance.frozenQty());
        repository.line(operationId, balance.goodId(), balance.locationId(), null, balance.id(), before, request.actualQuantity(), request.actualQuantity(), delta, balance.batchCode(), balance.qualityType(), request.remark());
        repository.transaction(scope.companyId(), scope.warehouseId(), scope.ownerId(), balance.id(), balance.goodId(), balance.locationId(), "COUNT", operationCode,
                before, delta, request.actualQuantity(), balance.availableQty(), delta, availableAfter,
                balance.frozenQty(), ZERO, balance.frozenQty(), null, request.idempotencyKey(), principal.userId(), request.remark());
        return new InventoryModels.OperationResult(operationCode, repository.view(balance.id(), scope.companyId(), scope.warehouseId(), scope.ownerId()));
    }

    @Transactional
    InventoryModels.SerialView createSerial(InventoryModels.SerialRequest request, WmsPrincipal principal, HttpSession session) {
        Scope scope = scope(principal, session);
        InventoryRepository.BalanceRow balance = lock(request.balanceId(), scope);
        if (repository.serialTracked(balance.id()).add(request.quantity()).compareTo(balance.total()) > 0) {
            throw new IllegalArgumentException("标签数量超过当前库存总量");
        }
        String serialCode = text(request.serialCode());
        if (serialCode.isEmpty()) serialCode = sequences.next("RFID", principal, session);
        String source = text(request.source()).toUpperCase();
        if (source.isEmpty()) source = "SYSTEM";
        if (!SERIAL_SOURCES.contains(source)) throw new IllegalArgumentException("标签来源不合法");
        long id = repository.createSerial(scope.companyId(), scope.warehouseId(), scope.ownerId(), balance, serialCode, request.quantity(), request.weightKg() == null ? ZERO : request.weightKg(), source);
        return repository.serial(id, scope.companyId(), scope.warehouseId(), scope.ownerId());
    }

    private InventoryRepository.BalanceRow lock(long balanceId, Scope scope) {
        return repository.lock(balanceId, scope.companyId(), scope.warehouseId(), scope.ownerId())
                .orElseThrow(() -> new IllegalArgumentException("库存不存在"));
    }

    private void rejectDuplicate(long companyId, String key) {
        if (repository.processed(companyId, key)) throw new IllegalArgumentException("该请求已处理，请勿重复提交");
    }

    private Scope scope(WmsPrincipal principal, HttpSession session) {
        AuthModels.TenantView tenant = tenants.current(principal, session);
        return new Scope(principal.companyId(), tenant.currentWarehouse().id(), tenant.currentOwner().id());
    }

    private String text(String value) { return value == null ? "" : value.trim(); }
    private String dimension(String value) {
        String normalized = text(value);
        return normalized.isEmpty() ? "-" : normalized;
    }
    private String quality(String value) {
        String normalized = text(value).toUpperCase();
        if (normalized.isEmpty()) normalized = "ZP";
        if (!QUALITY_TYPES.contains(normalized)) throw new IllegalArgumentException("质量类型不合法");
        return normalized;
    }
    private void requireNonNegative(BigDecimal value, String message) { if (value.signum() < 0) throw new IllegalArgumentException(message); }
    private record Scope(long companyId, long warehouseId, long ownerId) {}
}
