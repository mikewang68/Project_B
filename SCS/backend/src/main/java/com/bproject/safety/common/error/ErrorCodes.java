package com.bproject.safety.common.error;

/**
 * 统一错误码（字符串编码，稳定可被前端识别）。
 * 本阶段只提供基础错误码，业务错误码在后续业务模块追加。
 */
public final class ErrorCodes {
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String STATE_CONFLICT = "STATE_CONFLICT";
    public static final String UNPROCESSABLE_ENTITY = "UNPROCESSABLE_ENTITY";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
    }
}
