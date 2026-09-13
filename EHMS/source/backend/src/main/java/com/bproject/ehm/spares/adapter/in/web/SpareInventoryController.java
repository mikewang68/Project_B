package com.bproject.ehm.spares.adapter.in.web;

import com.bproject.ehm.spares.application.SpareInventoryApplicationService;
import com.bproject.ehm.spares.application.SpareInventorySummary;
import com.bproject.ehm.spares.application.SpareOperationResult;
import com.bproject.ehm.spares.application.SparePartView;
import com.bproject.ehm.spares.domain.model.SpareReservation;
import com.bproject.ehm.spares.domain.model.StockTransaction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/ehm/v1")
public class SpareInventoryController {
    private final SpareInventoryApplicationService inventory;

    public SpareInventoryController(SpareInventoryApplicationService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/spare-parts")
    public List<SparePartView> parts(@RequestParam(defaultValue = "MAIN") String warehouseCode) {
        return inventory.listParts(warehouseCode);
    }

    @PostMapping("/spare-parts")
    @ResponseStatus(HttpStatus.CREATED)
    public SparePartView create(@Valid @RequestBody CreatePartRequest request) {
        return inventory.createPart(request.partCode(), request.name(), request.specification(),
                request.category(), request.unit(), request.compatibleAssets(), request.safetyStock(),
                request.reorderPoint(), request.leadTimeDays(), request.unitCost(), request.warehouseCode());
    }

    @PostMapping("/spare-parts/{partCode}/receipt")
    public SpareOperationResult receipt(@PathVariable String partCode,
                                        @Valid @RequestBody QuantityOperationRequest request) {
        return inventory.receive(partCode, request.warehouseCode(), request.quantity(),
                request.reason(), request.operator());
    }

    @PostMapping("/spare-parts/{partCode}/return")
    public SpareOperationResult returnToStock(@PathVariable String partCode,
                                              @Valid @RequestBody ReturnRequest request) {
        return inventory.returnToStock(partCode, request.warehouseCode(), request.quantity(),
                request.workOrderNo(), request.reason(), request.operator());
    }

    @GetMapping("/spare-stock/summary")
    public SpareInventorySummary summary(@RequestParam(defaultValue = "MAIN") String warehouseCode) {
        return inventory.summary(warehouseCode);
    }

    @GetMapping("/spare-stock/alerts")
    public List<SparePartView> alerts(@RequestParam(defaultValue = "MAIN") String warehouseCode) {
        return inventory.lowStockAlerts(warehouseCode);
    }

    @GetMapping("/spare-stock/transactions")
    public List<StockTransaction> transactions(@RequestParam(required = false) String partCode,
                                               @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return inventory.listTransactions(partCode, limit);
    }

    @GetMapping("/spare-reservations")
    public List<SpareReservation> reservations(@RequestParam(required = false) String workOrderNo) {
        return inventory.listReservations(workOrderNo);
    }

    @PostMapping("/work-orders/{orderNo}/spare-reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public SpareOperationResult reserve(@PathVariable String orderNo,
                                        @Valid @RequestBody ReserveRequest request) {
        return inventory.reserve(orderNo, request.partCode(), request.warehouseCode(), request.quantity(),
                request.purpose(), request.requester());
    }

    @PostMapping("/spare-reservations/{reservationNo}/issue")
    public SpareOperationResult issue(@PathVariable String reservationNo,
                                      @Valid @RequestBody IssueRequest request) {
        return inventory.issue(reservationNo, request.quantity(), request.reason(), request.operator());
    }

    @PostMapping("/spare-reservations/{reservationNo}/release")
    public SpareOperationResult release(@PathVariable String reservationNo,
                                        @RequestBody(required = false) ReleaseRequest request) {
        ReleaseRequest value = request == null ? new ReleaseRequest(null, null) : request;
        return inventory.release(reservationNo, value.reason(), value.operator());
    }

    public record CreatePartRequest(
            @NotBlank(message = "备件编码不能为空") String partCode,
            @NotBlank(message = "备件名称不能为空") String name,
            String specification,
            String category,
            String unit,
            List<String> compatibleAssets,
            @DecimalMin(value = "0.0", message = "安全库存不能小于0") double safetyStock,
            @DecimalMin(value = "0.0", message = "补货点不能小于0") double reorderPoint,
            @Min(value = 0, message = "采购提前期不能小于0")
            @Max(value = 3650, message = "采购提前期不能大于3650天") int leadTimeDays,
            @DecimalMin(value = "0.0", message = "单价不能小于0") BigDecimal unitCost,
            String warehouseCode
    ) {
    }

    public record QuantityOperationRequest(
            @DecimalMin(value = "0.001", message = "数量必须大于0") double quantity,
            String warehouseCode,
            String reason,
            String operator
    ) {
    }

    public record ReturnRequest(
            @DecimalMin(value = "0.001", message = "数量必须大于0") double quantity,
            String warehouseCode,
            String workOrderNo,
            String reason,
            String operator
    ) {
    }

    public record ReserveRequest(
            @NotBlank(message = "备件编码不能为空") String partCode,
            @DecimalMin(value = "0.001", message = "数量必须大于0") double quantity,
            String warehouseCode,
            String purpose,
            String requester
    ) {
    }

    public record IssueRequest(
            @DecimalMin(value = "0.001", message = "数量必须大于0") double quantity,
            String reason,
            String operator
    ) {
    }

    public record ReleaseRequest(String reason, String operator) {
    }
}
