package com.bdemo.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<AjaxResult> validation(Exception exception) {
        return ResponseEntity.unprocessableEntity().body(AjaxResult.error(422, "请求参数校验失败"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<AjaxResult> database(DataAccessException exception) {
        log.error("Database operation failed", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(AjaxResult.error(503, "数据库暂不可用"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AjaxResult> business(IllegalArgumentException exception) {
        return ResponseEntity.unprocessableEntity().body(AjaxResult.error(422, exception.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<AjaxResult> business(BusinessException exception) {
        return ResponseEntity.status(exception.status())
                .body(AjaxResult.error(exception.status(), exception.getMessage()));
    }
}
