package com.bproject.trust.ingress;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(0)
@RestControllerAdvice(assignableTypes=GenericIngressController.class)
public class IngressErrorHandler {
  @ExceptionHandler(IngressError.class)
  ResponseEntity<?> ingress(IngressError e) { return ResponseEntity.status(e.status).body(e.body()); }
  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  ResponseEntity<?> persistence(Exception e) {
    var error=new IngressError(503,"PERSISTENCE_UNAVAILABLE","记录未确认接收，请按原事件号查询或重试");
    return ResponseEntity.status(error.status).body(error.body());
  }
}
