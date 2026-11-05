package com.mt.wms.stockin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=PsmsMachineController.class)
class PsmsMachineErrors {
    @ExceptionHandler(PsmsInboundException.class)
    ResponseEntity<Map<String,Object>> business(PsmsInboundException e,HttpServletRequest r){return error(e.status(),e.code(),e.getMessage(),r);}
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    ResponseEntity<Map<String,Object>> malformed(Exception e,HttpServletRequest r){return error(400,"PSMS_INVALID_REQUEST","字段格式或长度不符合入库联动契约",r);}
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String,Object>> conflict(Exception e,HttpServletRequest r){return error(409,"PSMS_CONFLICT","任务或事件存在冲突，请核对原请求",r);}
    private ResponseEntity<Map<String,Object>> error(int status,String code,String message,HttpServletRequest r){return ResponseEntity.status(status).body(Map.of("code",code,"message",message,"traceId",String.valueOf(r.getAttribute(com.mt.wms.common.web.RequestIdFilter.ATTRIBUTE))));}
}
