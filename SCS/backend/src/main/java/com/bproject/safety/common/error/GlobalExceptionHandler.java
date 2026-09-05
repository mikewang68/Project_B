package com.bproject.safety.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：成功响应严格按接口清单原样返回业务对象；
 * 所有错误统一为 {@link ApiError} 结构，Java 堆栈绝不直接返回前端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String MDC_TRACE_ID = "traceId";

    @ExceptionHandler(ApiException.class)
    public org.springframework.http.ResponseEntity<ApiError> handleApi(ApiException ex) {
        log.warn("业务异常 status={} code={} message={}", ex.status().value(), ex.code(), ex.getMessage());
        return ResponseEntity(ex.status(), ex.code(), ex.getMessage(), ex.details());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class
    })
    public ApiError handleValidation(BindException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ApiError.of(ErrorCodes.BAD_REQUEST, "请求参数校验失败", currentTraceId(),
                fields.isEmpty() ? null : Map.of("fields", fields));
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            ConstraintViolationException.class
    })
    public ApiError handleBadRequest(Exception ex) {
        Map<String, Object> details = null;
        if (ex instanceof ConstraintViolationException cve) {
            Map<String, Object> violations = new LinkedHashMap<>();
            for (ConstraintViolation<?> v : cve.getConstraintViolations()) {
                violations.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
            }
            details = Map.of("fields", violations);
        }
        return ApiError.of(ErrorCodes.BAD_REQUEST, "请求参数不合法: " + rootMessage(ex), currentTraceId(), details);
    }

    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ApiError handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ApiError.of("METHOD_NOT_ALLOWED", ex.getMessage(), currentTraceId(), null);
    }

    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ApiError handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return ApiError.of("UNSUPPORTED_MEDIA_TYPE",
                "不支持的 Content-Type，请使用 application/json", currentTraceId(), null);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ApiError handleNotFound(Exception ex) {
        String path = ex instanceof NoHandlerFoundException nhf
                ? nhf.getRequestURL()
                : ((NoResourceFoundException) ex).getResourcePath();
        return ApiError.of(ErrorCodes.NOT_FOUND, "请求的资源不存在: " + path, currentTraceId(), null);
    }

    /** 兜底：不向前端泄露堆栈，仅记录服务端日志。 */
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public ApiError handleUnexpected(HttpServletRequest request, Exception ex) {
        log.error("未处理异常 path={} {}", request.getRequestURI(), ex.toString(), ex);
        return ApiError.of(ErrorCodes.INTERNAL_ERROR, "服务内部错误，请稍后重试", currentTraceId(), null);
    }

    private static org.springframework.http.ResponseEntity<ApiError> ResponseEntity(
            HttpStatus status, String code, String message, Map<String, Object> details) {
        return org.springframework.http.ResponseEntity.status(status)
                .body(ApiError.of(code, message, currentTraceId(), details));
    }

    private static String rootMessage(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String currentTraceId() {
        String traceId = MDC.get(MDC_TRACE_ID);
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }
}
