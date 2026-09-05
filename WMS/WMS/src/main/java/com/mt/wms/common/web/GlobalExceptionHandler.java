package com.mt.wms.common.web;

import com.mt.wms.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数无效");
        return ResponseEntity.badRequest().body(ApiResponse.error("COMMON_VALIDATION_ERROR", message, requestId(request)));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiResponse<Void>> authentication(AuthenticationException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("AUTH_BAD_CREDENTIALS", exception.getMessage(), requestId(request)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> accessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("AUTH_FORBIDDEN", "无权执行此操作", requestId(request)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> illegalArgument(IllegalArgumentException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("COMMON_BAD_REQUEST", exception.getMessage(), requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("COMMON_INTERNAL_ERROR", "系统内部错误", requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }
}
