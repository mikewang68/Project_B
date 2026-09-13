package com.bproject.ehm.shared.web;

import com.bproject.ehm.shared.error.DomainConflictException;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import com.bproject.ehm.shared.error.ValidationException;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), List.of());
    }

    @ExceptionHandler({DomainConflictException.class, OptimisticLockingFailureException.class})
    ResponseEntity<ApiError> conflict(RuntimeException exception) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", exception.getMessage(), List.of());
    }

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ApiError> validation(ValidationException exception) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> beanValidation(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数校验失败", details);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "系统内部错误", List.of());
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message, List<String> details) {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, details, traceId, Instant.now()));
    }
}
