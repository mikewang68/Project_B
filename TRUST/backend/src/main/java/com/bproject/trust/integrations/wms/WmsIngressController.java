package com.bproject.trust.integrations.wms;

import com.bproject.trust.events.EventInput;
import com.bproject.trust.events.EventService;
import com.bproject.trust.identity.IntegrationSettings;
import com.bproject.trust.shared.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations/wms/events")
@PreAuthorize("hasAuthority('trust:source:submit')")
public class WmsIngressController {
  private final EventService events;

  public WmsIngressController(EventService events) {
    this.events = events;
  }

  private IntegrationSettings.Service service(HttpServletRequest request) {
    return (IntegrationSettings.Service) request.getAttribute("TRUST_SERVICE");
  }

  @PostMapping
  public ResponseEntity<?> submit(
      HttpServletRequest request, @Valid @RequestBody EventInput input) {
    var s = service(request);
    validate(s, input);
    return ResponseEntity.accepted()
        .body(events.submit(input, s.orgId(), "source:" + s.id(), null));
  }

  public static void validate(IntegrationSettings.Service s, EventInput input) {
    if (s == null || !s.sourceSystem().equals(input.sourceSystem()))
      throw new ApiError(403, "来源系统不匹配");
    if (!Set.of("WAREHOUSE_IN", "DISPATCH").contains(input.eventType()))
      throw new ApiError(400, "首批仅接收确认收货与实际发运");
    var d = input.details();
    if (d == null
        || !s.companyCode().equals(d.get("companyCode"))
        || !s.warehouseCode().equals(d.get("warehouseCode"))
        || !s.ownerCode().equals(d.get("ownerCode"))) throw new ApiError(403, "来源业务范围不匹配");
    if (d.get("operatorId") == null
        || d.get("operatorId").isBlank()
        || input.unit() == null
        || input.unit().isBlank()
        || input.quantity() == null
        || input.quantity().signum() <= 0
        || input.batchId() == null
        || input.batchId().isBlank()
        || input.batchId().equals("-")) throw new ApiError(400, "缺少真实操作身份、批次、数量或单位");
    if ("DISPATCH".equals(input.eventType())
        && (d.get("allocationId") == null
            || d.get("allocationId").isBlank()
            || input.relatedEventRefs() == null
            || input.relatedEventRefs().isEmpty())) throw new ApiError(400, "实际发运缺少库存分配或来源收货关联");
    if (input.relatedEventRefs() != null) {
      if ("WAREHOUSE_IN".equals(input.eventType()) && !input.relatedEventRefs().isEmpty())
        throw new ApiError(400, "首批收货事件不能声明发运来源关联");
      for (String ref : input.relatedEventRefs()) {
        if (ref == null || !ref.matches("[A-Za-z0-9._-]{1,80}:[A-Za-z0-9._-]{1,120}")
            || !ref.startsWith(s.sourceSystem() + ":")
            || ref.equals(input.sourceSystem() + ":" + input.sourceEventId()))
          throw new ApiError(400, "来源收货关联必须为同来源系统的其他事件号");
      }
    }
  }

  @GetMapping("/{id}")
  public Map<String, Object> status(HttpServletRequest request, @PathVariable String id) {
    var s = service(request);
    var e = events.get(id, s.orgId());
    if (!s.sourceSystem().equals(e.get("source_system"))
        || !("source:" + s.id()).equals(e.get("submitted_by"))) throw new ApiError(404, "来源记录不存在");
    return Map.of(
        "id",
        id,
        "fileState",
        e.get("file_state"),
        "chainState",
        e.get("chain_state"),
        "txId",
        java.util.Objects.toString(e.get("tx_id"), ""),
        "lastError",
        java.util.Objects.toString(e.get("last_error"), ""));
  }
}
