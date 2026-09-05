package com.bproject.safety.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 业务异常基类：携带 HTTP 状态、稳定错误码与可选 details。
 * 后续业务模块（如状态机非法流转抛 STATE_CONFLICT）复用它。
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.STATE_CONFLICT, message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.UNPROCESSABLE_ENTITY, message);
    }
}
