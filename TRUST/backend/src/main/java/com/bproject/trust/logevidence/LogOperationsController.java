package com.bproject.trust.logevidence;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit")
public class LogOperationsController {
  private final LogOperations operations;

  public LogOperationsController(LogOperations operations) {
    this.operations = operations;
  }

  @PostMapping("/page-actions")
  public Object page(Authentication a, @RequestBody LogOperations.PageAction input) {
    return operations.page(a, input);
  }

  @PostMapping("/page-actions/{id}/result")
  public Object result(
      Authentication a, @PathVariable String id, @RequestBody LogOperations.PageResult input) {
    return operations.result(a, id, input);
  }

  @GetMapping("/settings")
  public Object settings(Authentication a) {
    return operations.controls(a);
  }

  @PatchMapping("/settings")
  public Object settings(Authentication a, @RequestBody LogOperations.Controls input) {
    return operations.controls(a, input);
  }

  @GetMapping("/delivery")
  public Object deliveries(Authentication a) {
    return operations.deliveries(a);
  }

  @PostMapping("/delivery/{id}/retry")
  public Object retry(
      Authentication a, @PathVariable String id, @RequestBody LogOperations.RetryDelivery input) {
    return operations.retryDelivery(a, id, input);
  }
}
