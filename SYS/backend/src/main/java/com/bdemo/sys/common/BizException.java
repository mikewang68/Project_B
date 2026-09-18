package com.bdemo.sys.common;

public class BizException extends RuntimeException {

    private final int httpStatus;
    private final int code;

    public BizException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public int getCode() {
        return code;
    }

    public static BizException badRequest(String message) {
        return new BizException(400, message);
    }

    public static BizException unauthorized(String message) {
        return new BizException(401, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(403, message);
    }

    public static BizException notFound(String message) {
        return new BizException(404, message);
    }

    public static BizException conflict(String message) {
        return new BizException(409, message);
    }
}
